import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  ALERTMANAGER_SMOKE_ALERT_RULES,
  renderAlertmanagerSmokeRules,
} from './alertmanagerSmokeRules.mjs';

test('alertmanager smoke rules define one Slack warning and one PagerDuty critical alert', () => {
  assert.deepEqual(
    ALERTMANAGER_SMOKE_ALERT_RULES.map((rule) => ({
      alert: rule.alert,
      expr: rule.expr,
      severity: rule.labels.severity,
      releaseBlocking: rule.labels.release_blocking,
      smoke: rule.labels.smoke,
      owner: rule.labels.owner,
    })),
    [
      {
        alert: 'AlertmanagerSmokeWarning',
        expr: 'vector(1)',
        severity: 'warning',
        releaseBlocking: undefined,
        smoke: 'true',
        owner: 'platform-oncall',
      },
      {
        alert: 'AlertmanagerSmokeCritical',
        expr: 'vector(1)',
        severity: 'critical',
        releaseBlocking: 'true',
        smoke: 'true',
        owner: 'platform-oncall',
      },
    ],
  );
});

test('alertmanager smoke rule file stays in sync with renderer', () => {
  const file = readFileSync(
    new URL('../../infra/prometheus/smoke-rules/alertmanager-smoke.rules.yml', import.meta.url),
    'utf8',
  );

  assert.equal(file, renderAlertmanagerSmokeRules());
});

test('default prometheus config does not load smoke rules', () => {
  const file = readFileSync(
    new URL('../../infra/prometheus/prometheus.yml', import.meta.url),
    'utf8',
  );

  assert.match(file, /rule_files:\n  - \/etc\/prometheus\/rules\/\*\.rules\.yml/);
  assert.doesNotMatch(file, /smoke-rules/);
  assert.doesNotMatch(file, /AlertmanagerSmoke/);
});

test('alert smoke prometheus config loads normal and smoke rules', () => {
  const file = readFileSync(
    new URL('../../infra/prometheus/alert-smoke-prometheus.yml', import.meta.url),
    'utf8',
  );

  assert.match(file, /- \/etc\/prometheus\/rules\/\*\.rules\.yml/);
  assert.match(file, /- \/etc\/prometheus\/smoke-rules\/\*\.rules\.yml/);
  assert.match(file, /targets: \["alertmanager:9093"\]/);
});

test('compose isolates synthetic alert rules behind the alert-smoke profile', () => {
  const file = readFileSync(
    new URL('../../docker-compose.yml', import.meta.url),
    'utf8',
  );

  assert.match(file, /prometheus-alert-smoke:\n[\s\S]*profiles: \[ "alert-smoke" \]/);
  assert.match(file, /alertmanager:\n[\s\S]*profiles: \[ "cluster", "alert-smoke" \]/);
  assert.match(file, /\.\/infra\/prometheus\/alert-smoke-prometheus\.yml:\/etc\/prometheus\/prometheus\.yml:ro/);
  assert.match(file, /\.\/infra\/prometheus\/smoke-rules:\/etc\/prometheus\/smoke-rules:ro/);
  assert.match(file, /127\.0\.0\.1:\$\{PROMETHEUS_ALERT_SMOKE_PORT:-9094\}:9090/);
  assert.match(file, /prometheus_alert_smoke_data:/);
});
