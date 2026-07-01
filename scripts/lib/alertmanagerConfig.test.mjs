import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  ALERTMANAGER_CONFIG,
  renderAlertmanagerConfigForPagerDutyMode,
  renderAlertmanagerConfig,
  resolvePagerDutyRouteReceiver,
} from './alertmanagerConfig.mjs';

test('alertmanager routes warning alerts to Slack and paging alerts to PagerDuty', () => {
  assert.equal(ALERTMANAGER_CONFIG.route.receiver, 'slack-warning');
  assert.deepEqual(
    ALERTMANAGER_CONFIG.route.group_by,
    ['alertname', 'severity', 'release_gate', 'release_blocking', 'owner'],
  );
  assert.deepEqual(
    ALERTMANAGER_CONFIG.route.routes.map((route) => ({
      receiver: route.receiver,
      matchers: route.matchers,
    })),
    [
      {
        receiver: '${ALERTMANAGER_PAGERDUTY_RECEIVER}',
        matchers: ['release_blocking="true"'],
      },
      {
        receiver: '${ALERTMANAGER_PAGERDUTY_RECEIVER}',
        matchers: ['severity="critical"'],
      },
      {
        receiver: 'slack-warning',
        matchers: ['severity="warning"'],
      },
    ],
  );
});

test('alertmanager PagerDuty route receiver is templated for enable and disable modes', () => {
  const rendered = renderAlertmanagerConfig();

  assert.match(rendered, /receiver: "\$\{ALERTMANAGER_PAGERDUTY_RECEIVER\}"/);
  assert.match(rendered, /name: "pagerduty-critical"/);
  assert.match(rendered, /name: "slack-warning"/);
});

test('alertmanager config renders concrete PagerDuty on and off receivers for smoke validation', () => {
  assert.equal(resolvePagerDutyRouteReceiver(true), 'pagerduty-critical');
  assert.equal(resolvePagerDutyRouteReceiver(false), 'slack-warning');

  const pagerDutyOnConfig = renderAlertmanagerConfigForPagerDutyMode({ pagerDutyEnabled: true });
  assert.doesNotMatch(pagerDutyOnConfig, /\$\{ALERTMANAGER_PAGERDUTY_RECEIVER\}/);
  assert.match(pagerDutyOnConfig, /receiver: "pagerduty-critical"\n      matchers:\n        - "severity=\\"critical\\""/);

  const pagerDutyOffConfig = renderAlertmanagerConfigForPagerDutyMode({ pagerDutyEnabled: false });
  assert.doesNotMatch(pagerDutyOffConfig, /\$\{ALERTMANAGER_PAGERDUTY_RECEIVER\}/);
  assert.match(pagerDutyOffConfig, /receiver: "slack-warning"\n      matchers:\n        - "severity=\\"critical\\""/);
});

test('alertmanager receivers use file-based secrets instead of checked-in secret values', () => {
  const rendered = renderAlertmanagerConfig();

  assert.match(rendered, /api_url_file: "\/run\/secrets\/alertmanager_slack_webhook_url"/);
  assert.match(rendered, /routing_key_file: "\/run\/secrets\/alertmanager_pagerduty_routing_key"/);
  assert.doesNotMatch(rendered, /api_url: /);
  assert.doesNotMatch(rendered, /channel: /);
  assert.doesNotMatch(rendered, /routing_key: /);
  assert.doesNotMatch(rendered, /hooks\.slack\.com\/services\/[A-Z0-9]/);
  assert.doesNotMatch(rendered, /routing_key: "[a-f0-9]{32}"/);
});

test('alertmanager Slack notifications preserve per-alert annotations in grouped pages', () => {
  const [slackConfig] = ALERTMANAGER_CONFIG.receivers
    .find((receiver) => receiver.name === 'slack-warning')
    .slack_configs;

  assert.match(slackConfig.text, /range \.Alerts/);
  assert.match(slackConfig.text, /\.Annotations\.description/);
  assert.doesNotMatch(slackConfig.text, /\.CommonAnnotations\.description/);
});

test('alertmanager PagerDuty payload carries release gate and release-blocking context', () => {
  const [pagerDutyConfig] = ALERTMANAGER_CONFIG.receivers
    .find((receiver) => receiver.name === 'pagerduty-critical')
    .pagerduty_configs;

  assert.equal(pagerDutyConfig.class, '{{ .CommonLabels.release_gate }}');
  assert.equal(pagerDutyConfig.details.release_gate, '{{ .CommonLabels.release_gate }}');
  assert.equal(pagerDutyConfig.details.release_blocking, '{{ .CommonLabels.release_blocking }}');
});

test('alertmanager config file stays in sync with renderer', () => {
  const file = readFileSync(
    new URL('../../infra/alertmanager/alertmanager.yml', import.meta.url),
    'utf8',
  );

  assert.equal(file, renderAlertmanagerConfig());
});

test('prometheus sends alerts to the compose alertmanager service', () => {
  const file = readFileSync(
    new URL('../../infra/prometheus/prometheus.yml', import.meta.url),
    'utf8',
  );

  assert.match(file, /alerting:\n  alertmanagers:\n    - static_configs:\n        - targets: \["alertmanager:9093"\]/);
});

test('compose starts alertmanager with file-backed Slack and PagerDuty secrets', () => {
  const file = readFileSync(
    new URL('../../docker-compose.yml', import.meta.url),
    'utf8',
  );

  assert.match(file, /alertmanager:\n[\s\S]*image: \$\{ALERTMANAGER_IMAGE:-prom\/alertmanager:v0\.27\.0\}/);
  assert.match(file, /ALERTMANAGER_PAGERDUTY_ENABLED: \$\{ALERTMANAGER_PAGERDUTY_ENABLED:-true\}/);
  assert.match(file, /\.\/infra\/alertmanager\/alertmanager-entrypoint\.sh:\/usr\/local\/bin\/alertmanager-entrypoint\.sh:ro/);
  assert.match(file, /\/bin\/sh/);
  assert.match(file, /\/usr\/local\/bin\/alertmanager-entrypoint\.sh/);
  assert.match(file, /--config\.file=\/tmp\/alertmanager\/alertmanager\.yml/);
  assert.doesNotMatch(file, /--config\.expand-env/);
  assert.match(file, /source: alertmanager_slack_webhook_url/);
  assert.match(file, /source: alertmanager_pagerduty_routing_key/);
  assert.match(file, /file: \$\{ALERTMANAGER_SLACK_WEBHOOK_URL_FILE:-\.\/infra\/alertmanager\/secrets\/alertmanager_slack_webhook_url_sample\}/);
  assert.match(file, /file: \$\{ALERTMANAGER_PAGERDUTY_ROUTING_KEY_FILE:-\.\/infra\/alertmanager\/secrets\/alertmanager_pagerduty_routing_key_sample\}/);
  assert.match(file, /\.\/infra\/alertmanager\/alertmanager\.yml:\/etc\/alertmanager\/alertmanager\.yml:ro/);
  assert.match(file, /alertmanager_data:/);
});

test('alertmanager entrypoint maps PagerDuty enabled flag to the route receiver', () => {
  const entrypoint = new URL('../../infra/alertmanager/alertmanager-entrypoint.sh', import.meta.url);

  assert.equal(existsSync(entrypoint), true);

  const file = existsSync(entrypoint) ? readFileSync(entrypoint, 'utf8') : '';

  assert.match(file, /ALERTMANAGER_PAGERDUTY_ENABLED/);
  assert.match(file, /ALERTMANAGER_PAGERDUTY_RECEIVER=pagerduty-critical/);
  assert.match(file, /ALERTMANAGER_PAGERDUTY_RECEIVER=slack-warning/);
  assert.match(file, /rendered_config=\/tmp\/alertmanager\/alertmanager\.yml/);
  assert.match(file, /sed "s\|\\\$\{ALERTMANAGER_PAGERDUTY_RECEIVER\}\|\$\{ALERTMANAGER_PAGERDUTY_RECEIVER\}\|g"/);
  assert.match(file, /exec \/bin\/alertmanager/);
});

test('alertmanager secret samples are tracked while real secret file names are ignored', () => {
  const slackSample = new URL(
    '../../infra/alertmanager/secrets/alertmanager_slack_webhook_url_sample',
    import.meta.url,
  );
  const pagerDutySample = new URL(
    '../../infra/alertmanager/secrets/alertmanager_pagerduty_routing_key_sample',
    import.meta.url,
  );
  const gitignore = readFileSync(new URL('../../.gitignore', import.meta.url), 'utf8');

  assert.equal(existsSync(slackSample), true);
  assert.equal(existsSync(pagerDutySample), true);
  assert.match(gitignore, /infra\/alertmanager\/secrets\/alertmanager_slack_webhook_url\n/);
  assert.match(gitignore, /infra\/alertmanager\/secrets\/alertmanager_pagerduty_routing_key\n/);
  assert.doesNotMatch(gitignore, /alertmanager_slack_webhook_url_sample/);
  assert.doesNotMatch(gitignore, /alertmanager_pagerduty_routing_key_sample/);
});

test('readme documents required Alertmanager secret setup', () => {
  const readme = readFileSync(new URL('../../README.md', import.meta.url), 'utf8');

  assert.match(readme, /alertmanager_slack_webhook_url_sample/);
  assert.match(readme, /alertmanager_pagerduty_routing_key_sample/);
  assert.match(readme, /alertmanager_slack_webhook_url/);
  assert.match(readme, /alertmanager_pagerduty_routing_key/);
  assert.match(readme, /Slack-compatible webhook URL/);
  assert.match(readme, /PagerDuty Events API v2 Integration Key/);
  assert.match(readme, /sample secret/);
  assert.match(readme, /ALERTMANAGER_SLACK_WEBHOOK_URL_FILE/);
  assert.match(readme, /ALERTMANAGER_PAGERDUTY_ROUTING_KEY_FILE/);
  assert.match(readme, /ALERTMANAGER_PAGERDUTY_ENABLED/);
  assert.match(readme, /\[PagerDuty Events API v2 Integration Key 발급 절차\]\(docs\/pagerduty_events_api_v2_integration_key\.md\)/);
  assert.doesNotMatch(readme, /Add integration/);
});

test('alertmanager on-call docs link to the PagerDuty Events API v2 key issuance guide', () => {
  const docs = readFileSync(
    new URL('../../docs/alertmanager_oncall_wiring.md', import.meta.url),
    'utf8',
  );

  assert.match(docs, /PagerDuty Events API v2 Integration Key/);
  assert.match(docs, /\[PagerDuty Events API v2 Integration Key 발급 절차\]\(\.\/pagerduty_events_api_v2_integration_key\.md\)/);
  assert.match(docs, /routing_key_file/);
  assert.match(docs, /ALERTMANAGER_PAGERDUTY_ENABLED/);
  assert.match(docs, /PagerDuty를 끄면 critical/);
  assert.match(docs, /for receiver in pagerduty-critical slack-warning/);
  assert.match(docs, /amtool[\s\S]*check-config/);
  assert.doesNotMatch(docs, /### PagerDuty key 발급 절차/);
});

test('pagerduty guide documents Events API v2 key issuance steps', () => {
  const docs = readFileSync(
    new URL('../../docs/pagerduty_events_api_v2_integration_key.md', import.meta.url),
    'utf8',
  );

  assert.match(docs, /PagerDuty Events API v2 Integration Key/);
  assert.match(docs, /https:\/\/www\.pagerduty\.com\/integrations\//);
  assert.match(docs, /Service Directory/);
  assert.match(docs, /chatting-service/);
  assert.match(docs, /escalation policy/);
  assert.match(docs, /Add integration/);
  assert.match(docs, /Integration Type/);
  assert.match(docs, /Events API v2/);
  assert.match(docs, /Integration Key/);
  assert.match(docs, /alertmanager_pagerduty_routing_key/);
});
