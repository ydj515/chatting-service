export const ALERTMANAGER_SMOKE_ALERT_GROUP = 'alertmanager-smoke';

export const ALERTMANAGER_SMOKE_ALERT_RULES = [
  {
    alert: 'AlertmanagerSmokeWarning',
    expr: 'vector(1)',
    labels: {
      severity: 'warning',
      phase: 'smoke',
      release_gate: 'alertmanager_delivery',
      smoke: 'true',
      owner: 'platform-oncall',
    },
    annotations: {
      summary: 'Alertmanager warning delivery smoke test',
      description: 'Synthetic warning alert used to verify Prometheus to Alertmanager to Slack-compatible webhook delivery.',
      runbook: 'docs/alertmanager_oncall_wiring.md',
    },
  },
  {
    alert: 'AlertmanagerSmokeCritical',
    expr: 'vector(1)',
    labels: {
      severity: 'critical',
      phase: 'smoke',
      release_gate: 'alertmanager_delivery',
      release_blocking: 'true',
      smoke: 'true',
      owner: 'platform-oncall',
    },
    annotations: {
      summary: 'Alertmanager critical delivery smoke test',
      description: 'Synthetic critical alert used to verify Prometheus to Alertmanager to PagerDuty delivery.',
      runbook: 'docs/alertmanager_oncall_wiring.md',
    },
  },
];

export function renderAlertmanagerSmokeRules({
  groupName = ALERTMANAGER_SMOKE_ALERT_GROUP,
  rules = ALERTMANAGER_SMOKE_ALERT_RULES,
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
