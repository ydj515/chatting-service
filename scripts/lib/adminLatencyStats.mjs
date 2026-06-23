export function percentile(values, percentileValue) {
  if (!values.length) {
    return null;
  }
  const sorted = [...values].sort((left, right) => left - right);
  const rank = Math.ceil(percentileValue * sorted.length);
  const index = Math.min(Math.max(rank - 1, 0), sorted.length - 1);
  return sorted[index];
}

export function summarizeSamples(samples, { targetP95Ms }) {
  const latencies = samples.map((sample) => sample.latencyMs);
  const successes = samples.filter((sample) => sample.ok).length;
  const failures = samples.length - successes;
  const p95Ms = percentile(latencies, 0.95);

  return {
    requests: samples.length,
    successes,
    failures,
    minMs: latencies.length ? Math.min(...latencies) : null,
    maxMs: latencies.length ? Math.max(...latencies) : null,
    p50Ms: percentile(latencies, 0.5),
    p95Ms,
    p99Ms: percentile(latencies, 0.99),
    passedTarget: p95Ms != null && p95Ms <= targetP95Ms && failures === 0,
    statusCounts: samples.reduce((acc, sample) => {
      const key = String(sample.status);
      acc[key] = (acc[key] ?? 0) + 1;
      return acc;
    }, {}),
  };
}

export function summarizeGateSamples(samples, { gate, targetMs }) {
  const summary = summarizeSamples(samples, { targetP95Ms: targetMs });
  const targetMetric = gate === 'cold' ? 'p99Ms' : 'p95Ms';
  const measuredMs = summary[targetMetric];

  return {
    ...summary,
    gate,
    targetMetric,
    targetMs,
    measuredMs,
    passedTarget: measuredMs != null && measuredMs <= targetMs && summary.failures === 0,
  };
}

export function summarizeGateReport(results) {
  const failedGates = results.flatMap((result) =>
    result.gateResults
      .filter((gateResult) => !gateResult.summary.passedTarget)
      .map((gateResult) => {
        const percentileName = gateResult.summary.targetMetric.replace('Ms', '');
        return `admin_search_${gateResult.gate}_${percentileName}:${result.name}`;
      }),
  );

  return {
    ok: failedGates.length === 0,
    failedGates,
  };
}
