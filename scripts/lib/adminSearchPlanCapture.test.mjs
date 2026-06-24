import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  buildAdminSearchExplainSql,
  buildSlowQueryCaptureRequests,
  slowQueryPlanCaptureModeValue,
} from './adminSearchPlanCapture.mjs';

test('buildSlowQueryCaptureRequests selects only failed cold gate results', () => {
  const requests = buildSlowQueryCaptureRequests([
    {
      name: 'history',
      url: 'http://localhost/api/admin/chat-rooms/30001/messages?limit=50',
      gateResults: [
        {
          gate: 'warm',
          summary: {
            passedTarget: false,
            targetMetric: 'p95Ms',
            measuredMs: 1500,
          },
        },
      ],
    },
    {
      name: 'search',
      url: 'http://localhost/api/admin/messages/search?q=hello&mode=FTS&roomId=30001&limit=50',
      gateResults: [
        {
          gate: 'cold',
          summary: {
            passedTarget: false,
            targetMetric: 'p99Ms',
            measuredMs: 7000,
          },
        },
      ],
    },
  ], { mode: 'on-cold-failure' });

  assert.equal(requests.length, 1);
  assert.equal(requests[0].endpointName, 'search');
  assert.equal(requests[0].gate, 'cold');
  assert.equal(requests[0].targetMetric, 'p99Ms');
});

test('buildAdminSearchExplainSql emits safe FTS EXPLAIN SQL for the search endpoint', () => {
  const sql = buildAdminSearchExplainSql({
    name: 'search',
    query: "hello 'quoted' keyword",
    searchMode: 'FTS',
    roomId: 30001,
    from: '2026-06-13T11:46:50',
    to: '2026-06-14T15:33:25',
    limit: 50,
  });

  assert.match(sql, /^EXPLAIN \(ANALYZE, BUFFERS, FORMAT JSON\)/);
  assert.match(sql, /cm\.content_tsv @@ plainto_tsquery\('simple', 'hello ''quoted'' keyword'\)/);
  assert.match(sql, /cm\.room_id = 30001/);
  assert.match(sql, /cm\.created_at >= TIMESTAMPTZ '2026-06-13T11:46:50'/);
  assert.match(sql, /ORDER BY cm\.created_at DESC, cm\.room_seq DESC, cm\.message_id DESC/);
});

test('buildAdminSearchExplainSql emits bounded history EXPLAIN SQL', () => {
  const sql = buildAdminSearchExplainSql({
    name: 'history',
    roomId: 30001,
    from: '2026-06-13T11:46:50',
    to: '2026-06-14T15:33:25',
    limit: 50,
  });

  assert.match(sql, /WHERE cm\.room_id = 30001/);
  assert.match(sql, /ORDER BY cm\.room_seq DESC, cm\.created_at DESC, cm\.message_id DESC/);
  assert.match(sql, /LIMIT 50/);
});

test('slowQueryPlanCaptureModeValue accepts explicit modes only', () => {
  assert.equal(slowQueryPlanCaptureModeValue(undefined), 'off');
  assert.equal(slowQueryPlanCaptureModeValue('off'), 'off');
  assert.equal(slowQueryPlanCaptureModeValue('on-cold-failure'), 'on-cold-failure');
  assert.throws(
    () => slowQueryPlanCaptureModeValue('always'),
    /--slow-query-plan must be one of off, on-cold-failure/,
  );
});
