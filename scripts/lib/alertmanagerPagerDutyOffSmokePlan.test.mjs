import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { test } from 'node:test';
import {
  buildPagerDutyOffSmokePlan,
  parsePagerDutyOffSmokeArgs,
  runPagerDutyOffSmoke,
} from './alertmanagerPagerDutyOffSmokePlan.mjs';

test('parsePagerDutyOffSmokeArgs defaults to a dry-run PagerDuty-off Slack smoke', () => {
  const options = parsePagerDutyOffSmokeArgs([]);

  assert.equal(options.execute, false);
  assert.equal(options.pagerDutyEnabled, false);
  assert.equal(options.cleanup, true);
  assert.equal(options.slackWebhookUrlFile, './infra/alertmanager/secrets/alertmanager_slack_webhook_url_sample');
  assert.equal(options.pagerDutyRoutingKeyFile, './infra/alertmanager/secrets/alertmanager_pagerduty_routing_key_sample');
  assert.equal(options.prometheusAlertSmokeUrl, 'http://localhost:9094');
  assert.equal(options.alertmanagerUrl, 'http://localhost:9093');
  assert.equal(options.chatAdminToken, 'alert-smoke-unused-admin-token');
});

test('parsePagerDutyOffSmokeArgs keeps an existing Compose admin token', () => {
  const options = parsePagerDutyOffSmokeArgs([], {
    CHAT_ADMIN_TOKEN: 'existing-admin-token',
  });

  assert.equal(options.chatAdminToken, 'existing-admin-token');
});

test('buildPagerDutyOffSmokePlan starts alert-smoke with PagerDuty disabled and restores Alertmanager', () => {
  const plan = buildPagerDutyOffSmokePlan(parsePagerDutyOffSmokeArgs([
    '--slack-webhook-url-file',
    '.secrets/slack',
    '--pagerduty-routing-key-file',
    '.secrets/pagerduty',
  ]));

  assert.equal(plan.env.ALERTMANAGER_PAGERDUTY_ENABLED, 'false');
  assert.equal(plan.env.ALERTMANAGER_SLACK_WEBHOOK_URL_FILE, '.secrets/slack');
  assert.equal(plan.env.ALERTMANAGER_PAGERDUTY_ROUTING_KEY_FILE, '.secrets/pagerduty');
  assert.equal(plan.env.CHAT_ADMIN_TOKEN, 'alert-smoke-unused-admin-token');
  assert.equal(plan.restoreEnv.ALERTMANAGER_PAGERDUTY_ENABLED, 'true');
  assert.equal(plan.restoreEnv.CHAT_ADMIN_TOKEN, 'alert-smoke-unused-admin-token');
  assert.deepEqual(plan.startCommand, [
    'docker',
    'compose',
    '--profile',
    'alert-smoke',
    'up',
    '-d',
    'alertmanager',
    'prometheus-alert-smoke',
  ]);
  assert.deepEqual(plan.cleanupCommands, [
    ['docker', 'compose', '--profile', 'alert-smoke', 'stop', 'prometheus-alert-smoke'],
    ['docker', 'compose', '--profile', 'alert-smoke', 'rm', '-f', 'prometheus-alert-smoke'],
    ['docker', 'compose', '--profile', 'alert-smoke', 'up', '-d', 'alertmanager'],
  ]);
});

test('runPagerDutyOffSmoke refuses actual execution with sample Slack secret by default', async () => {
  await assert.rejects(
    runPagerDutyOffSmoke(parsePagerDutyOffSmokeArgs(['--execute']), {
      readFile: async () => 'https://hooks.slack.com/services/~~~~~',
    }),
    /real Slack webhook secret/,
  );
});

test('runPagerDutyOffSmoke executes actual Slack delivery smoke when explicitly requested', async () => {
  const commands = [];
  const slept = [];
  const options = parsePagerDutyOffSmokeArgs([
    '--execute',
    '--allow-sample-secret',
    '--delivery-wait-ms',
    '1',
  ]);

  const result = await runPagerDutyOffSmoke(options, {
    readFile: async () => 'https://hooks.slack.com/services/real/team/token',
    runCommand: async (command, args, { env }) => {
      commands.push([command, ...args, env.ALERTMANAGER_PAGERDUTY_ENABLED, env.CHAT_ADMIN_TOKEN]);
    },
    sleep: async (millis) => slept.push(millis),
    fetchJson: async (url) => {
      if (url.includes('/api/v1/alerts')) {
        return {
          status: 'success',
          data: {
            alerts: [
              { labels: { alertname: 'AlertmanagerSmokeWarning', severity: 'warning' }, state: 'firing' },
              {
                labels: {
                  alertname: 'AlertmanagerSmokeCritical',
                  severity: 'critical',
                  release_blocking: 'true',
                },
                state: 'firing',
              },
            ],
          },
        };
      }
      if (url.includes('/api/v2/alerts')) {
        return [
          { labels: { alertname: 'AlertmanagerSmokeCritical', severity: 'critical' }, status: { state: 'active' } },
        ];
      }
      if (url.includes('/api/v1/query')) {
        return { status: 'success', data: { result: [] } };
      }
      throw new Error(`unexpected url ${url}`);
    },
  });

  assert.equal(result.ok, true);
  assert.equal(result.execute, true);
  assert.equal(result.pagerDutyEnabled, false);
  assert.deepEqual(slept, [1]);
  assert.deepEqual(commands[0], [
    'docker',
    'compose',
    '--profile',
    'alert-smoke',
    'up',
    '-d',
    'alertmanager',
    'prometheus-alert-smoke',
    'false',
    'alert-smoke-unused-admin-token',
  ]);
  assert.deepEqual(commands.at(-1), [
    'docker',
    'compose',
    '--profile',
    'alert-smoke',
    'up',
    '-d',
    'alertmanager',
    'true',
    'alert-smoke-unused-admin-token',
  ]);
  assert.equal(result.checked.includes('slack-delivery-wait'), true);
  assert.equal(result.checked.includes('pagerduty-off-routing'), true);
  assert.equal(result.checked.includes('pagerduty-notification-failure-metric'), true);
});

test('runPagerDutyOffSmoke cleans up and restores Alertmanager after a post-start failure', async () => {
  const commands = [];
  const options = parsePagerDutyOffSmokeArgs([
    '--execute',
    '--allow-sample-secret',
    '--timeout-ms',
    '1',
    '--poll-interval-ms',
    '1',
    '--delivery-wait-ms',
    '0',
  ]);

  await assert.rejects(
    runPagerDutyOffSmoke(options, {
      runCommand: async (command, args, { env }) => {
        commands.push([command, ...args, env.ALERTMANAGER_PAGERDUTY_ENABLED]);
      },
      sleep: async (millis) => new Promise((resolve) => {
        setTimeout(resolve, millis);
      }),
      fetchJson: async () => {
        throw new Error('connect ECONNREFUSED 127.0.0.1:9094');
      },
    }),
    /Prometheus smoke alerts did not start firing before timeout/,
  );

  assert.deepEqual(commands, [
    ['docker', 'compose', '--profile', 'alert-smoke', 'up', '-d', 'alertmanager', 'prometheus-alert-smoke', 'false'],
    ['docker', 'compose', '--profile', 'alert-smoke', 'stop', 'prometheus-alert-smoke', 'false'],
    ['docker', 'compose', '--profile', 'alert-smoke', 'rm', '-f', 'prometheus-alert-smoke', 'false'],
    ['docker', 'compose', '--profile', 'alert-smoke', 'up', '-d', 'alertmanager', 'true'],
  ]);
});

test('runPagerDutyOffSmoke keeps polling through transient startup fetch errors', async () => {
  let prometheusAlertAttempts = 0;
  const options = parsePagerDutyOffSmokeArgs([
    '--execute',
    '--allow-sample-secret',
    '--delivery-wait-ms',
    '0',
  ]);

  const result = await runPagerDutyOffSmoke(options, {
    runCommand: async () => {},
    sleep: async () => {},
    fetchJson: async (url) => {
      if (url.includes('/api/v1/alerts')) {
        prometheusAlertAttempts += 1;
        if (prometheusAlertAttempts === 1) {
          throw new Error('connect ECONNREFUSED 127.0.0.1:9094');
        }
        return prometheusSmokeAlertsBody();
      }
      if (url.includes('/api/v2/alerts')) {
        return [
          { labels: { alertname: 'AlertmanagerSmokeCritical', severity: 'critical' }, status: { state: 'active' } },
        ];
      }
      if (url.includes('/api/v1/query')) {
        return { status: 'success', data: { result: [] } };
      }
      throw new Error(`unexpected url ${url}`);
    },
  });

  assert.equal(result.ok, true);
  assert.equal(prometheusAlertAttempts, 2);
});

test('runPagerDutyOffSmoke rejects suppressed Alertmanager smoke alerts', async () => {
  const options = parsePagerDutyOffSmokeArgs([
    '--execute',
    '--allow-sample-secret',
    '--timeout-ms',
    '1',
    '--poll-interval-ms',
    '1',
    '--delivery-wait-ms',
    '0',
  ]);

  await assert.rejects(
    runPagerDutyOffSmoke(options, {
      runCommand: async () => {},
      sleep: async (millis) => new Promise((resolve) => {
        setTimeout(resolve, millis);
      }),
      fetchJson: async (url) => {
        if (url.includes('/api/v1/alerts')) {
          return prometheusSmokeAlertsBody();
        }
        if (url.includes('/api/v2/alerts')) {
          return [
            { labels: { alertname: 'AlertmanagerSmokeCritical', severity: 'critical' }, status: { state: 'suppressed' } },
          ];
        }
        if (url.includes('/api/v1/query')) {
          return { status: 'success', data: { result: [] } };
        }
        throw new Error(`unexpected url ${url}`);
      },
    }),
    /Alertmanager did not receive AlertmanagerSmokeCritical before timeout/,
  );
});

test('runPagerDutyOffSmoke fails when PagerDuty notification failures are observed', async () => {
  const options = parsePagerDutyOffSmokeArgs([
    '--execute',
    '--allow-sample-secret',
    '--delivery-wait-ms',
    '0',
  ]);

  await assert.rejects(
    runPagerDutyOffSmoke(options, {
      runCommand: async () => {},
      sleep: async () => {},
      fetchJson: async (url) => {
        if (url.includes('/api/v1/alerts')) {
          return prometheusSmokeAlertsBody();
        }
        if (url.includes('/api/v2/alerts')) {
          return [
            { labels: { alertname: 'AlertmanagerSmokeCritical', severity: 'critical' }, status: { state: 'active' } },
          ];
        }
        if (url.includes('/api/v1/query')) {
          const value = url.includes('pagerduty') ? '1' : '0';
          return { status: 'success', data: { result: [{ value: [1710000000, value] }] } };
        }
        throw new Error(`unexpected url ${url}`);
      },
    }),
    /PagerDuty notification failures observed during PagerDuty-off smoke: 1/,
  );
});

test('runPagerDutyOffSmoke keeps HTTP status context for non-JSON startup responses', async () => {
  const server = createServer((request, response) => {
    response.writeHead(503, { 'Content-Type': 'text/plain' });
    response.end(`warming up: ${request.url}`);
  });

  await new Promise((resolve) => {
    server.listen(0, '127.0.0.1', resolve);
  });

  try {
    const { port } = server.address();
    const options = parsePagerDutyOffSmokeArgs([
      '--execute',
      '--allow-sample-secret',
      '--timeout-ms',
      '1',
      '--poll-interval-ms',
      '1',
      '--delivery-wait-ms',
      '0',
      '--prometheus-alert-smoke-url',
      `http://127.0.0.1:${port}`,
    ]);

    await assert.rejects(
      runPagerDutyOffSmoke(options, {
        runCommand: async () => {},
        sleep: async (millis) => new Promise((resolve) => {
          setTimeout(resolve, millis);
        }),
      }),
      /503: warming up/,
    );
  } finally {
    await new Promise((resolve, reject) => {
      server.close((error) => (error ? reject(error) : resolve()));
    });
  }
});

function prometheusSmokeAlertsBody() {
  return {
    status: 'success',
    data: {
      alerts: [
        { labels: { alertname: 'AlertmanagerSmokeWarning', severity: 'warning' }, state: 'firing' },
        {
          labels: {
            alertname: 'AlertmanagerSmokeCritical',
            severity: 'critical',
            release_blocking: 'true',
          },
          state: 'firing',
        },
      ],
    },
  };
}

test('PagerDuty-off smoke CLI delegates to the reusable plan runner', () => {
  const script = new URL('../alertmanager-pagerduty-off-smoke.mjs', import.meta.url);

  assert.equal(existsSync(script), true);

  const file = existsSync(script) ? readFileSync(script, 'utf8') : '';
  assert.match(file, /parsePagerDutyOffSmokeArgs\(process\.argv\.slice\(2\), process\.env\)/);
  assert.match(file, /runPagerDutyOffSmoke\(options\)/);
});

test('PagerDuty-off Slack smoke is documented and available as a mise task', () => {
  const docs = readFileSync(
    new URL('../../docs/alertmanager_oncall_wiring.md', import.meta.url),
    'utf8',
  );
  const mise = readFileSync(new URL('../../mise.toml', import.meta.url), 'utf8');

  assert.match(docs, /scripts\/alertmanager-pagerduty-off-smoke\.mjs --execute/);
  assert.match(docs, /ALERTMANAGER_PAGERDUTY_ENABLED=false/);
  assert.match(docs, /Slack delivery smoke/);
  assert.match(mise, /\[tasks\."verify:alertmanager-pagerduty-off-smoke"\]/);
  assert.match(mise, /node scripts\/alertmanager-pagerduty-off-smoke\.mjs --execute/);
});
