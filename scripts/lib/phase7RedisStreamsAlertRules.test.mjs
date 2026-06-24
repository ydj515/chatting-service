import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  REDIS_STREAMS_LAG_ALERT_RULES,
  renderRedisStreamsLagAlertRules,
} from './phase7RedisStreamsAlertRules.mjs';

test('redis streams lag alert rules define warning and critical thresholds', () => {
  assert.equal(REDIS_STREAMS_LAG_ALERT_RULES.length, 4);

  assert.deepEqual(
    REDIS_STREAMS_LAG_ALERT_RULES.map((rule) => [rule.alert, rule.expr, rule.for, rule.labels.severity]),
    [
      [
        'RedisStreamsGroupLagSustained',
        'max by (consumer_group, stream_shard) (chat_redis_stream_group_lag) > 0',
        '3m',
        'warning',
      ],
      [
        'RedisStreamsGroupLagCritical',
        'max by (consumer_group, stream_shard) (chat_redis_stream_group_lag) > 1000',
        '5m',
        'critical',
      ],
      [
        'RedisStreamsGroupPendingSustained',
        'max by (consumer_group, stream_shard) (chat_redis_stream_group_pending) > 0',
        '5m',
        'warning',
      ],
      [
        'RedisStreamsGroupPendingCritical',
        'max by (consumer_group, stream_shard) (chat_redis_stream_group_pending) > 100',
        '10m',
        'critical',
      ],
    ],
  );
});

test('redis streams lag alert rules keep metric labels bounded', () => {
  const rendered = renderRedisStreamsLagAlertRules();

  assert.match(rendered, /consumer_group/);
  assert.match(rendered, /stream_shard/);
  assert.doesNotMatch(rendered, /roomId|room_id|stream_key|room_stream|chat:stream:/);
});

test('redis streams lag prometheus rule file stays in sync with renderer', () => {
  const file = readFileSync(
    new URL('../../infra/prometheus/rules/phase7-redis-streams-lag.rules.yml', import.meta.url),
    'utf8',
  );

  assert.equal(file, renderRedisStreamsLagAlertRules());
});
