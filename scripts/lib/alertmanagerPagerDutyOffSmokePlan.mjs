import { spawn } from 'node:child_process';

export const DEFAULT_ALERTMANAGER_PORT = '9093';
export const DEFAULT_PROMETHEUS_ALERT_SMOKE_PORT = '9094';
export const DEFAULT_SLACK_WEBHOOK_URL_FILE = './infra/alertmanager/secrets/alertmanager_slack_webhook_url_sample';
export const DEFAULT_PAGERDUTY_ROUTING_KEY_FILE = './infra/alertmanager/secrets/alertmanager_pagerduty_routing_key_sample';

const DEFAULT_TIMEOUT_MS = 120000;
const DEFAULT_POLL_INTERVAL_MS = 3000;
const DEFAULT_DELIVERY_WAIT_MS = 45000;

export function parsePagerDutyOffSmokeArgs(args, env = {}) {
  const options = {
    execute: false,
    allowSampleSecret: false,
    cleanup: true,
    pagerDutyEnabled: false,
    slackWebhookUrlFile: env.ALERTMANAGER_SLACK_WEBHOOK_URL_FILE ?? DEFAULT_SLACK_WEBHOOK_URL_FILE,
    pagerDutyRoutingKeyFile: env.ALERTMANAGER_PAGERDUTY_ROUTING_KEY_FILE ?? DEFAULT_PAGERDUTY_ROUTING_KEY_FILE,
    prometheusAlertSmokeUrl: env.CHAT_PROMETHEUS_ALERT_SMOKE_URL
      ?? `http://localhost:${env.PROMETHEUS_ALERT_SMOKE_PORT ?? DEFAULT_PROMETHEUS_ALERT_SMOKE_PORT}`,
    alertmanagerUrl: env.CHAT_ALERTMANAGER_URL
      ?? `http://localhost:${env.ALERTMANAGER_PORT ?? DEFAULT_ALERTMANAGER_PORT}`,
    timeoutMs: numberFromEnv(env.ALERTMANAGER_SMOKE_TIMEOUT_MS, DEFAULT_TIMEOUT_MS),
    pollIntervalMs: numberFromEnv(env.ALERTMANAGER_SMOKE_POLL_INTERVAL_MS, DEFAULT_POLL_INTERVAL_MS),
    deliveryWaitMs: numberFromEnv(env.ALERTMANAGER_SMOKE_DELIVERY_WAIT_MS, DEFAULT_DELIVERY_WAIT_MS),
  };

  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    switch (arg) {
      case '--execute':
        options.execute = true;
        break;
      case '--allow-sample-secret':
        options.allowSampleSecret = true;
        break;
      case '--keep-running':
      case '--no-cleanup':
        options.cleanup = false;
        break;
      case '--cleanup':
        options.cleanup = true;
        break;
      case '--slack-webhook-url-file':
        options.slackWebhookUrlFile = requireValue(args, index, arg);
        index += 1;
        break;
      case '--pagerduty-routing-key-file':
        options.pagerDutyRoutingKeyFile = requireValue(args, index, arg);
        index += 1;
        break;
      case '--prometheus-alert-smoke-url':
        options.prometheusAlertSmokeUrl = trimTrailingSlash(requireValue(args, index, arg));
        index += 1;
        break;
      case '--alertmanager-url':
        options.alertmanagerUrl = trimTrailingSlash(requireValue(args, index, arg));
        index += 1;
        break;
      case '--timeout-ms':
        options.timeoutMs = parsePositiveInteger(requireValue(args, index, arg), arg);
        index += 1;
        break;
      case '--poll-interval-ms':
        options.pollIntervalMs = parsePositiveInteger(requireValue(args, index, arg), arg);
        index += 1;
        break;
      case '--delivery-wait-ms':
        options.deliveryWaitMs = parsePositiveInteger(requireValue(args, index, arg), arg);
        index += 1;
        break;
      default:
        throw new Error(`unknown argument: ${arg}`);
    }
  }

  options.prometheusAlertSmokeUrl = trimTrailingSlash(options.prometheusAlertSmokeUrl);
  options.alertmanagerUrl = trimTrailingSlash(options.alertmanagerUrl);
  return options;
}

export function buildPagerDutyOffSmokePlan(options) {
  return {
    env: {
      ALERTMANAGER_PAGERDUTY_ENABLED: 'false',
      ALERTMANAGER_SLACK_WEBHOOK_URL_FILE: options.slackWebhookUrlFile,
      ALERTMANAGER_PAGERDUTY_ROUTING_KEY_FILE: options.pagerDutyRoutingKeyFile,
    },
    startCommand: [
      'docker',
      'compose',
      '--profile',
      'alert-smoke',
      'up',
      '-d',
      'alertmanager',
      'prometheus-alert-smoke',
    ],
    cleanupCommands: [
      ['docker', 'compose', '--profile', 'alert-smoke', 'stop', 'prometheus-alert-smoke'],
      ['docker', 'compose', '--profile', 'alert-smoke', 'rm', '-f', 'prometheus-alert-smoke'],
    ],
  };
}

export async function runPagerDutyOffSmoke(options, deps = {}) {
  const plan = buildPagerDutyOffSmokePlan(options);

  if (!options.execute) {
    return {
      ok: true,
      execute: false,
      pagerDutyEnabled: false,
      plan,
      checked: ['dry-run-plan', 'pagerduty-off-routing'],
      nextStep: 'Run again with --execute and a real Slack webhook secret to send the smoke alert.',
    };
  }

  await assertRealSlackSecret(options, deps);

  const runCommand = deps.runCommand ?? runProcess;
  const fetchJson = deps.fetchJson ?? fetchJsonWithTimeout;
  const sleep = deps.sleep ?? sleepMillis;
  const commandEnv = { ...process.env, ...plan.env };

  await runCommand(plan.startCommand[0], plan.startCommand.slice(1), { env: commandEnv });
  await waitForPrometheusSmokeAlerts(options, fetchJson, sleep);
  await waitForAlertmanagerCriticalSmoke(options, fetchJson, sleep);
  await sleep(options.deliveryWaitMs);

  const [slackNotificationFailures, pagerDutyNotificationFailures] = await Promise.all([
    queryPrometheusNumber(
      options.prometheusAlertSmokeUrl,
      'increase(alertmanager_notification_requests_failed_total{integration="slack"}[5m])',
      fetchJson,
    ),
    queryPrometheusNumber(
      options.prometheusAlertSmokeUrl,
      'increase(alertmanager_notification_requests_failed_total{integration="pagerduty"}[5m])',
      fetchJson,
    ),
  ]);

  if (slackNotificationFailures > 0) {
    throw new Error(`Slack notification failures observed during PagerDuty-off smoke: ${slackNotificationFailures}`);
  }

  if (options.cleanup) {
    for (const cleanupCommand of plan.cleanupCommands) {
      await runCommand(cleanupCommand[0], cleanupCommand.slice(1), { env: commandEnv });
    }
  }

  return {
    ok: true,
    execute: true,
    pagerDutyEnabled: false,
    cleanup: options.cleanup,
    prometheusAlertSmokeUrl: options.prometheusAlertSmokeUrl,
    alertmanagerUrl: options.alertmanagerUrl,
    notificationFailures: {
      slack: slackNotificationFailures,
      pagerduty: pagerDutyNotificationFailures,
    },
    checked: [
      'pagerduty-off-routing',
      'prometheus-smoke-warning-firing',
      'prometheus-smoke-critical-firing',
      'alertmanager-critical-smoke-active',
      'slack-delivery-wait',
      'slack-notification-failure-metric',
    ],
    manualVerification: 'Confirm AlertmanagerSmokeCritical arrived in the configured Slack channel.',
  };
}

async function assertRealSlackSecret(options, deps) {
  if (options.allowSampleSecret) {
    return;
  }
  const readFile = deps.readFile ?? readTextFile;
  const value = await readFile(options.slackWebhookUrlFile, 'utf8');
  if (isSampleSlackSecret(options.slackWebhookUrlFile, value)) {
    throw new Error(
      'actual Slack delivery smoke requires a real Slack webhook secret; pass --slack-webhook-url-file or --allow-sample-secret for a non-delivery dry run.',
    );
  }
}

function isSampleSlackSecret(filePath, value) {
  return filePath.endsWith('_sample')
    || value.trim().length === 0
    || value.includes('~~~~~')
    || value.includes('/XXX/YYY/ZZZ');
}

async function waitForPrometheusSmokeAlerts(options, fetchJson, sleep) {
  await waitUntil(options, sleep, async () => {
    const body = await fetchJson(`${options.prometheusAlertSmokeUrl}/api/v1/alerts`);
    const alerts = body?.data?.alerts ?? [];
    const hasWarning = alerts.some((alert) =>
      alert.state === 'firing'
        && alert.labels?.alertname === 'AlertmanagerSmokeWarning'
        && alert.labels?.severity === 'warning'
    );
    const hasCritical = alerts.some((alert) =>
      alert.state === 'firing'
        && alert.labels?.alertname === 'AlertmanagerSmokeCritical'
        && alert.labels?.severity === 'critical'
        && alert.labels?.release_blocking === 'true'
    );
    return hasWarning && hasCritical;
  }, 'Prometheus smoke alerts did not start firing before timeout.');
}

async function waitForAlertmanagerCriticalSmoke(options, fetchJson, sleep) {
  await waitUntil(options, sleep, async () => {
    const alerts = await fetchJson(`${options.alertmanagerUrl}/api/v2/alerts`);
    return Array.isArray(alerts) && alerts.some((alert) =>
      alert.labels?.alertname === 'AlertmanagerSmokeCritical'
        && alert.labels?.severity === 'critical'
        && (alert.status?.state === 'active' || alert.status?.state === 'suppressed')
    );
  }, 'Alertmanager did not receive AlertmanagerSmokeCritical before timeout.');
}

async function waitUntil(options, sleep, predicate, timeoutMessage) {
  const deadline = Date.now() + options.timeoutMs;
  while (Date.now() <= deadline) {
    if (await predicate()) {
      return;
    }
    await sleep(options.pollIntervalMs);
  }
  throw new Error(timeoutMessage);
}

async function queryPrometheusNumber(prometheusUrl, query, fetchJson) {
  const url = new URL('/api/v1/query', prometheusUrl);
  url.searchParams.set('query', query);
  const body = await fetchJson(url.toString());
  if (body?.status !== 'success') {
    throw new Error(`Prometheus query failed: ${query}`);
  }
  return sumPrometheusVector(body.data?.result ?? []);
}

function sumPrometheusVector(result) {
  return result.reduce((sum, item) => {
    const value = Number(item.value?.[1] ?? 0);
    return Number.isFinite(value) ? sum + value : sum;
  }, 0);
}

async function fetchJsonWithTimeout(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(10000) });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(`GET ${url} failed with ${response.status}: ${JSON.stringify(body)}`);
  }
  return body;
}

function runProcess(command, args, { env }) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { env, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (chunk) => {
      stdout += chunk.toString('utf8');
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk.toString('utf8');
    });
    child.on('error', reject);
    child.on('close', (code) => {
      if (code !== 0) {
        reject(new Error(`${command} ${args.join(' ')} failed with code ${code}: ${stderr.trim()}`));
        return;
      }
      resolve(stdout.trim());
    });
  });
}

async function readTextFile(filePath) {
  const { readFile } = await import('node:fs/promises');
  return readFile(filePath, 'utf8');
}

function sleepMillis(millis) {
  return new Promise((resolve) => {
    setTimeout(resolve, millis);
  });
}

function numberFromEnv(value, fallback) {
  return value === undefined ? fallback : parsePositiveInteger(value, 'environment value');
}

function parsePositiveInteger(value, name) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed) || parsed < 0 || String(parsed) !== String(value)) {
    throw new Error(`${name} must be a non-negative integer`);
  }
  return parsed;
}

function requireValue(args, index, name) {
  const value = args[index + 1];
  if (value === undefined || value.startsWith('--')) {
    throw new Error(`${name} requires a value`);
  }
  return value;
}

function trimTrailingSlash(value) {
  return value.replace(/\/+$/, '');
}
