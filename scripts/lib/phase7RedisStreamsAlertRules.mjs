export const REDIS_STREAMS_LAG_ALERT_GROUP = 'phase7-redis-streams-lag';

export const REDIS_STREAMS_LAG_ALERT_RULES = [
  {
    alert: 'RedisStreamsGroupLagSustained',
    expr: 'max by (consumer_group, stream_shard) (chat_redis_stream_group_lag) > 0',
    for: '3m',
    labels: {
      severity: 'warning',
      phase: 'phase7',
      release_gate: 'redis_streams_lag',
    },
    annotations: {
      summary: 'Redis Streams group lag is sustained',
      description: 'Redis Streams group lag has stayed above zero for 3 minutes on consumer_group={{ $labels.consumer_group }}, stream_shard={{ $labels.stream_shard }}.',
      runbook: 'docs/phase7_redis_streams_lag_alert_rule.md',
    },
  },
  {
    alert: 'RedisStreamsGroupLagCritical',
    expr: 'max by (consumer_group, stream_shard) (chat_redis_stream_group_lag) > 1000',
    for: '5m',
    labels: {
      severity: 'critical',
      phase: 'phase7',
      release_gate: 'redis_streams_lag',
    },
    annotations: {
      summary: 'Redis Streams group lag crossed the critical threshold',
      description: 'Redis Streams group lag is above 1000 entries for 5 minutes on consumer_group={{ $labels.consumer_group }}, stream_shard={{ $labels.stream_shard }}.',
      runbook: 'docs/phase7_redis_streams_lag_alert_rule.md',
    },
  },
  {
    alert: 'RedisStreamsGroupPendingSustained',
    expr: 'max by (consumer_group, stream_shard) (chat_redis_stream_group_pending) > 0',
    for: '5m',
    labels: {
      severity: 'warning',
      phase: 'phase7',
      release_gate: 'redis_streams_pending',
    },
    annotations: {
      summary: 'Redis Streams pending entries are sustained',
      description: 'Redis Streams pending entries have stayed above zero for 5 minutes on consumer_group={{ $labels.consumer_group }}, stream_shard={{ $labels.stream_shard }}.',
      runbook: 'docs/phase7_redis_streams_lag_alert_rule.md',
    },
  },
  {
    alert: 'RedisStreamsGroupPendingCritical',
    expr: 'max by (consumer_group, stream_shard) (chat_redis_stream_group_pending) > 100',
    for: '10m',
    labels: {
      severity: 'critical',
      phase: 'phase7',
      release_gate: 'redis_streams_pending',
    },
    annotations: {
      summary: 'Redis Streams pending entries crossed the critical threshold',
      description: 'Redis Streams pending entries are above 100 for 10 minutes on consumer_group={{ $labels.consumer_group }}, stream_shard={{ $labels.stream_shard }}.',
      runbook: 'docs/phase7_redis_streams_lag_alert_rule.md',
    },
  },
];

export function renderRedisStreamsLagAlertRules({
  groupName = REDIS_STREAMS_LAG_ALERT_GROUP,
  rules = REDIS_STREAMS_LAG_ALERT_RULES,
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
