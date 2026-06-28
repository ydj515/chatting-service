import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  ALERTMANAGER_CONFIG,
  renderAlertmanagerConfig,
} from './alertmanagerConfig.mjs';

test('alertmanager routes warning alerts to Slack and paging alerts to PagerDuty', () => {
  assert.equal(ALERTMANAGER_CONFIG.route.receiver, 'slack-warning');
  assert.deepEqual(
    ALERTMANAGER_CONFIG.route.routes.map((route) => ({
      receiver: route.receiver,
      matchers: route.matchers,
    })),
    [
      {
        receiver: 'pagerduty-critical',
        matchers: ['release_blocking="true"'],
      },
      {
        receiver: 'pagerduty-critical',
        matchers: ['severity="critical"'],
      },
      {
        receiver: 'slack-warning',
        matchers: ['severity="warning"'],
      },
    ],
  );
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
  assert.doesNotMatch(file, /--config\.expand-env/);
  assert.match(file, /source: alertmanager_slack_webhook_url/);
  assert.match(file, /source: alertmanager_pagerduty_routing_key/);
  assert.match(file, /file: \$\{ALERTMANAGER_SLACK_WEBHOOK_URL_FILE:-\.\/infra\/alertmanager\/secrets\/alertmanager_slack_webhook_url\}/);
  assert.match(file, /file: \$\{ALERTMANAGER_PAGERDUTY_ROUTING_KEY_FILE:-\.\/infra\/alertmanager\/secrets\/alertmanager_pagerduty_routing_key\}/);
  assert.match(file, /\.\/infra\/alertmanager\/alertmanager\.yml:\/etc\/alertmanager\/alertmanager\.yml:ro/);
  assert.match(file, /alertmanager_data:/);
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
