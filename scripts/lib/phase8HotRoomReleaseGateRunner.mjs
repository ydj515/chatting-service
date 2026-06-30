import {
  assertLoadSummary,
  assertPrometheusSnapshot,
  buildFailedGateResult,
  buildGateResult,
  buildStageSuccess,
} from './phase8HotRoomReleaseGatePlan.mjs';

export async function runReleaseGate(options, deps) {
  const stageResults = [];
  for (const stage of options.stages) {
    try {
      const loadSummary = await deps.runLoadChat(stage, options);
      assertLoadSummary(loadSummary, stage, options);
      const prometheusSnapshot = await deps.readPrometheusSnapshot(options.prometheusUrl, stage, options);
      assertPrometheusSnapshot(prometheusSnapshot, options);
      stageResults.push(buildStageSuccess(stage, loadSummary, prometheusSnapshot));
    } catch (error) {
      return buildFailedGateResult(options, stage, error, stageResults);
    }
  }
  return buildGateResult(options, stageResults);
}
