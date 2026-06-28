import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  ALERTMANAGER_SELF_MONITORING_ALERT_RULES,
  renderAlertmanagerSelfMonitoringRules,
} from './alertmanagerSelfMonitoringRules.mjs';

test('alertmanager self-monitoring rules send PagerDuty delivery failures to Slack only', () => {
  assert.equal(ALERTMANAGER_SELF_MONITORING_ALERT_RULES.length, 1);

  const [rule] = ALERTMANAGER_SELF_MONITORING_ALERT_RULES;

  assert.equal(rule.alert, 'AlertmanagerPagerDutyNotificationFailures');
  assert.equal(
    rule.expr,
    'increase(alertmanager_notification_requests_failed_total{integration="pagerduty"}[5m]) > 0',
  );
  assert.equal(rule.for, '1m');
  assert.equal(rule.labels.severity, 'warning');
  assert.equal(rule.labels.owner, 'platform-oncall');
  assert.equal(rule.labels.failed_integration, 'pagerduty');
  assert.equal(rule.labels.release_blocking, undefined);
});

test('alertmanager self-monitoring rules keep labels bounded and avoid PagerDuty paging metadata', () => {
  const rendered = renderAlertmanagerSelfMonitoringRules();

  assert.match(rendered, /alertmanager_notification_requests_failed_total\{integration="pagerduty"\}/);
  assert.match(rendered, /severity: "warning"/);
  assert.match(rendered, /failed_integration: "pagerduty"/);
  assert.doesNotMatch(rendered, /severity: "critical"|release_blocking/);
  assert.doesNotMatch(rendered, /receiver|routing_key|service_key|incident_key/);
});

test('alertmanager self-monitoring prometheus rule file stays in sync with renderer', () => {
  const file = readFileSync(
    new URL('../../infra/prometheus/rules/alertmanager-self-monitoring.rules.yml', import.meta.url),
    'utf8',
  );

  assert.equal(file, renderAlertmanagerSelfMonitoringRules());
});

test('prometheus profiles scrape Alertmanager metrics for notification failure self-monitoring', () => {
  for (const configPath of [
    '../../infra/prometheus/prometheus.yml',
    '../../infra/prometheus/alert-smoke-prometheus.yml',
  ]) {
    const file = readFileSync(
      new URL(configPath, import.meta.url),
      'utf8',
    );

    assert.match(file, /job_name: alertmanager/);
    assert.match(file, /targets: \["alertmanager:9093"\]/);
  }
});
