export const ALERTMANAGER_PAGERDUTY_ROUTE_RECEIVER_TEMPLATE = '${ALERTMANAGER_PAGERDUTY_RECEIVER}';
export const ALERTMANAGER_PAGERDUTY_ENABLED_RECEIVER = 'pagerduty-critical';
export const ALERTMANAGER_PAGERDUTY_DISABLED_RECEIVER = 'slack-warning';

export const ALERTMANAGER_CONFIG = {
  global: {
    resolve_timeout: '5m',
  },
  route: {
    receiver: 'slack-warning',
    group_by: ['alertname', 'severity', 'release_gate', 'release_blocking', 'owner'],
    group_wait: '30s',
    group_interval: '5m',
    repeat_interval: '4h',
    routes: [
      {
        receiver: ALERTMANAGER_PAGERDUTY_ROUTE_RECEIVER_TEMPLATE,
        matchers: ['release_blocking="true"'],
        group_wait: '10s',
        repeat_interval: '30m',
      },
      {
        receiver: ALERTMANAGER_PAGERDUTY_ROUTE_RECEIVER_TEMPLATE,
        matchers: ['severity="critical"'],
        group_wait: '10s',
        repeat_interval: '30m',
      },
      {
        receiver: 'slack-warning',
        matchers: ['severity="warning"'],
      },
    ],
  },
  receivers: [
    {
      name: 'slack-warning',
      slack_configs: [
        {
          api_url_file: '/run/secrets/alertmanager_slack_webhook_url',
          send_resolved: true,
          title: '{{ .CommonLabels.alertname }} ({{ .CommonLabels.severity }})',
          text: '{{ range .Alerts }}{{ .Annotations.summary }}\n{{ .Annotations.description }}\nRunbook: {{ .Annotations.runbook }}\n{{ end }}Owner: {{ .CommonLabels.owner }}',
        },
      ],
    },
    {
      name: 'pagerduty-critical',
      pagerduty_configs: [
        {
          routing_key_file: '/run/secrets/alertmanager_pagerduty_routing_key',
          severity: 'critical',
          class: '{{ .CommonLabels.release_gate }}',
          component: '{{ .CommonLabels.alertname }}',
          group: '{{ .CommonLabels.owner }}',
          send_resolved: true,
          description: '{{ .CommonAnnotations.summary }}',
          details: {
            description: '{{ .CommonAnnotations.description }}',
            runbook: '{{ .CommonAnnotations.runbook }}',
            release_gate: '{{ .CommonLabels.release_gate }}',
            release_blocking: '{{ .CommonLabels.release_blocking }}',
            owner: '{{ .CommonLabels.owner }}',
          },
        },
      ],
    },
  ],
};

export function renderAlertmanagerConfig({
  config = ALERTMANAGER_CONFIG,
} = {}) {
  return `${renderYaml(config)}\n`;
}

export function resolvePagerDutyRouteReceiver(pagerDutyEnabled = true) {
  return pagerDutyEnabled
    ? ALERTMANAGER_PAGERDUTY_ENABLED_RECEIVER
    : ALERTMANAGER_PAGERDUTY_DISABLED_RECEIVER;
}

export function renderAlertmanagerConfigForPagerDutyMode({
  config = ALERTMANAGER_CONFIG,
  pagerDutyEnabled = true,
} = {}) {
  return renderAlertmanagerConfig({ config })
    .replaceAll(
      ALERTMANAGER_PAGERDUTY_ROUTE_RECEIVER_TEMPLATE,
      resolvePagerDutyRouteReceiver(pagerDutyEnabled),
    );
}

function renderYaml(value, indent = 0) {
  if (Array.isArray(value)) {
    return value.map((item) => renderArrayItem(item, indent)).join('\n');
  }
  if (isPlainObject(value)) {
    return Object.entries(value)
      .map(([key, item]) => renderObjectEntry(key, item, indent))
      .join('\n');
  }
  return quoteYaml(value);
}

function renderObjectEntry(key, value, indent) {
  const prefix = spaces(indent);
  if (Array.isArray(value) || isPlainObject(value)) {
    return `${prefix}${key}:\n${renderYaml(value, indent + 2)}`;
  }
  return `${prefix}${key}: ${quoteYaml(value)}`;
}

function renderArrayItem(value, indent) {
  const prefix = spaces(indent);
  if (isPlainObject(value)) {
    const [firstEntry, ...restEntries] = Object.entries(value);
    const [firstKey, firstValue] = firstEntry;
    const first = `${prefix}- ${firstKey}:${renderNestedValue(firstValue, indent + 2)}`;
    const rest = restEntries
      .map(([key, item]) => renderObjectEntry(key, item, indent + 2))
      .join('\n');
    return rest.length > 0 ? `${first}\n${rest}` : first;
  }
  if (Array.isArray(value)) {
    return `${prefix}-\n${renderYaml(value, indent + 2)}`;
  }
  return `${prefix}- ${quoteYaml(value)}`;
}

function renderNestedValue(value, indent) {
  if (Array.isArray(value) || isPlainObject(value)) {
    return `\n${renderYaml(value, indent)}`;
  }
  return ` ${quoteYaml(value)}`;
}

function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function spaces(count) {
  return ' '.repeat(count);
}

function quoteYaml(value) {
  if (typeof value === 'boolean') {
    return value ? 'true' : 'false';
  }
  return JSON.stringify(String(value));
}
