import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  ROOM_SEQ_GAP_ALERT_RULES,
  renderRoomSeqGapAlertRules,
} from './phase8RoomSeqGapAlertRules.mjs';

test('room sequence gap alert rules define warning-only bounded aggregate alerts', () => {
  assert.equal(ROOM_SEQ_GAP_ALERT_RULES.length, 2);

  assert.deepEqual(
    ROOM_SEQ_GAP_ALERT_RULES.map((rule) => [rule.alert, rule.expr, rule.for, rule.labels.severity]),
    [
      [
        'RoomSeqGapDetected',
        'max(chat_room_seq_gap_missing_sequences) > 0',
        '2m',
        'warning',
      ],
      [
        'RoomSeqGapWidthElevated',
        'max(chat_room_seq_gap_max_width) > 100',
        '5m',
        'warning',
      ],
    ],
  );
});

test('room sequence gap alert rules keep labels bounded', () => {
  const rendered = renderRoomSeqGapAlertRules();

  assert.match(rendered, /chat_room_seq_gap_missing_sequences/);
  assert.match(rendered, /chat_room_seq_gap_max_width/);
  assert.doesNotMatch(rendered, /roomId|room_id|message_id|stream_key|chat:stream:/);
});

test('room sequence gap prometheus rule file stays in sync with renderer', () => {
  const file = readFileSync(
    new URL('../../infra/prometheus/rules/phase8-room-seq-gap.rules.yml', import.meta.url),
    'utf8',
  );

  assert.equal(file, renderRoomSeqGapAlertRules());
});
