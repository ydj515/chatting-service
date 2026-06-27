export const ROOM_SEQ_GAP_ALERT_GROUP = 'phase8-room-seq-gap-audit';

export const ROOM_SEQ_GAP_ALERT_RULES = [
  {
    alert: 'RoomSeqGapDetected',
    expr: 'max(chat_room_seq_gap_missing_sequences) > 0',
    for: '2m',
    labels: {
      severity: 'warning',
      phase: 'phase8',
      release_gate: 'room_seq_gap',
    },
    annotations: {
      summary: 'Canonical room sequence gap detected',
      description: 'The aggregate roomSeq gap audit found missing sequence numbers for at least 2 minutes. This can indicate Redis trim/loss or a failed append after sequence allocation; triage with Redis append failures and worker lag before paging as data loss.',
      runbook: 'docs/infrastructure.md',
    },
  },
  {
    alert: 'RoomSeqGapWidthElevated',
    expr: 'max(chat_room_seq_gap_max_width) > 100',
    for: '5m',
    labels: {
      severity: 'warning',
      phase: 'phase8',
      release_gate: 'room_seq_gap',
    },
    annotations: {
      summary: 'Canonical room sequence gap width is elevated',
      description: 'The largest aggregate roomSeq gap width is above 100 for 5 minutes. Treat this as a sequence-hole warning and confirm whether accepted messages were lost before escalating.',
      runbook: 'docs/infrastructure.md',
    },
  },
];

export function renderRoomSeqGapAlertRules({
  groupName = ROOM_SEQ_GAP_ALERT_GROUP,
  rules = ROOM_SEQ_GAP_ALERT_RULES,
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
