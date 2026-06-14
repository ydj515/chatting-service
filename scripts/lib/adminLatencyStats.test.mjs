import assert from 'node:assert/strict';
import { test } from 'node:test';
import { percentile, summarizeSamples } from './adminLatencyStats.mjs';

test('percentile uses nearest-rank semantics for latency samples', () => {
  const samples = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100];

  assert.equal(percentile(samples, 0.5), 50);
  assert.equal(percentile(samples, 0.95), 100);
});

test('summarizeSamples reports request count and p95 latency', () => {
  const summary = summarizeSamples(
    [
      { ok: true, status: 200, latencyMs: 20 },
      { ok: true, status: 200, latencyMs: 40 },
      { ok: false, status: 500, latencyMs: 60 },
    ],
    { targetP95Ms: 50 },
  );

  assert.equal(summary.requests, 3);
  assert.equal(summary.successes, 2);
  assert.equal(summary.failures, 1);
  assert.equal(summary.p95Ms, 60);
  assert.equal(summary.passedTarget, false);
});
