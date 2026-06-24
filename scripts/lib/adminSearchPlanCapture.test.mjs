import assert from 'node:assert/strict';
import { chmod, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { test } from 'node:test';
import {
  buildAdminSearchExplainSql,
  buildSlowQueryPlanArtifactPath,
  captureSlowQueryPlans,
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

test('buildSlowQueryPlanArtifactPath defaults measuredAt to a timestamp', () => {
  const artifactPath = buildSlowQueryPlanArtifactPath({
    outputDir: 'docs/performance/admin-search-slow-query-plans',
    endpointName: 'search',
    gate: 'cold',
    targetMetric: 'p99Ms',
  });

  assert.doesNotMatch(artifactPath, /undefined/);
  assert.match(artifactPath, /search_cold_p99_explain\.json$/);
});

test('captureSlowQueryPlans fails a hanging psql process with a timeout', async () => {
  const tempDir = await mkdtemp(join(tmpdir(), 'admin-search-plan-capture-'));
  const originalPath = process.env.PATH;
  try {
    const fakePsql = join(tempDir, 'psql');
    await writeFile(fakePsql, '#!/bin/sh\nsleep 1\n', 'utf8');
    await chmod(fakePsql, 0o755);
    process.env.PATH = `${tempDir}:${originalPath ?? ''}`;

    const captures = await captureSlowQueryPlans(
      [
        {
          endpointName: 'search',
          gate: 'cold',
          targetMetric: 'p99Ms',
          measuredMs: 7000,
          targetMs: 6000,
        },
      ],
      {
        db: { host: 'localhost', port: '5432', user: 'chatuser', password: 'chatpass', name: 'chatdb' },
        psqlMode: 'local',
        psqlService: 'postgres-replica',
        outputDir: join(tempDir, 'plans'),
        measuredAt: '2026-06-24T00:00:00.000Z',
        slowQueryPlanTimeoutMs: 10,
        planOptions: {
          query: 'hello',
          searchMode: 'FTS',
          roomId: 30001,
          limit: 50,
        },
      },
    );

    assert.equal(captures.length, 1);
    assert.equal(captures[0].status, 'failed');
    assert.match(captures[0].error, /timed out after 10ms/);
  } finally {
    process.env.PATH = originalPath;
    await rm(tempDir, { recursive: true, force: true });
  }
});
