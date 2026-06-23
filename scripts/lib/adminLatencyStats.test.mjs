import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  percentile,
  summarizeGateReport,
  summarizeGateSamples,
  summarizeSamples,
} from './adminLatencyStats.mjs';

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

test('summarizeGateSamples separates warm p95 and cold p99 thresholds', () => {
  const samples = [
    { ok: true, status: 200, latencyMs: 100 },
    { ok: true, status: 200, latencyMs: 200 },
    { ok: true, status: 200, latencyMs: 300 },
    { ok: true, status: 200, latencyMs: 7000 },
  ];

  const warm = summarizeGateSamples(samples, {
    gate: 'warm',
    targetMs: 1000,
  });
  const cold = summarizeGateSamples(samples, {
    gate: 'cold',
    targetMs: 6000,
  });

  assert.equal(warm.targetMetric, 'p95Ms');
  assert.equal(warm.measuredMs, 7000);
  assert.equal(warm.passedTarget, false);
  assert.equal(cold.targetMetric, 'p99Ms');
  assert.equal(cold.measuredMs, 7000);
  assert.equal(cold.passedTarget, false);
});

test('summarizeGateReport returns ok false with stable failed gate names', () => {
  const report = summarizeGateReport([
    {
      name: 'history',
      gateResults: [
        {
          gate: 'warm',
          summary: {
            passedTarget: true,
            targetMetric: 'p95Ms',
          },
        },
      ],
    },
    {
      name: 'search',
      gateResults: [
        {
          gate: 'cold',
          summary: {
            passedTarget: false,
            targetMetric: 'p99Ms',
          },
        },
      ],
    },
  ]);

  assert.equal(report.ok, false);
  assert.deepEqual(report.failedGates, ['admin_search_cold_p99:search']);
});

test('summarizeGateReport handles missing gate result fields defensively', () => {
  const report = summarizeGateReport([
    {
      name: 'history',
    },
    {
      name: 'search',
      gateResults: [
        {
          gate: 'cold',
        },
      ],
    },
  ]);

  assert.equal(report.ok, false);
  assert.deepEqual(report.failedGates, ['admin_search_cold_unknown:search']);
});
