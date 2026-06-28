export const ALERTMANAGER_SELF_MONITORING_ALERT_GROUP = 'alertmanager-self-monitoring';

export const ALERTMANAGER_SELF_MONITORING_ALERT_RULES = [
  {
    alert: 'AlertmanagerPagerDutyNotificationFailures',
    expr: 'increase(alertmanager_notification_requests_failed_total{integration="pagerduty"}[5m]) > 0',
    for: '1m',
    labels: {
      severity: 'warning',
      phase: 'operations',
      release_gate: 'alertmanager_notification_delivery',
      owner: 'platform-oncall',
      failed_integration: 'pagerduty',
    },
    annotations: {
      summary: 'Alertmanager PagerDuty notification delivery is failing',
      description: 'Alertmanager has failed at least one PagerDuty notification request in the last 5 minutes. Check the PagerDuty Events API v2 Integration Key, network/DNS reachability, PagerDuty service status, and Alertmanager logs before relying on critical paging.',
      runbook: 'docs/alertmanager_oncall_wiring.md',
    },
  },
];

export function renderAlertmanagerSelfMonitoringRules({
  groupName = ALERTMANAGER_SELF_MONITORING_ALERT_GROUP,
  rules = ALERTMANAGER_SELF_MONITORING_ALERT_RULES,
} = {}) {
  return [
    'groups:',
    `  - name: ${groupName}`,
    '    rules:',
    ...rules.flatMap(renderRule),
    '',
  ].join('\n');
}

function renderRule(rule) {
  const lines = [
    `      - alert: ${rule.alert}`,
    `        expr: ${rule.expr}`,
    `        for: ${rule.for}`,
  ];

  appendMapping(lines, 'labels', rule.labels);
  appendMapping(lines, 'annotations', rule.annotations);

  return lines;
}

function appendMapping(lines, name, values) {
  const entries = Object.entries(values ?? {});
  if (entries.length === 0) {
    return;
  }
  lines.push(`        ${name}:`);
  lines.push(...entries.map(([key, value]) => `          ${key}: ${quoteYaml(value)}`));
}

function quoteYaml(value) {
  return JSON.stringify(String(value));
}
