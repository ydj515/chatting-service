#!/usr/bin/env node
import { spawn } from 'node:child_process';
import {
  assertLoadSummary,
  assertPrometheusSnapshot,
  buildLoadChatArgs,
  parsePhase8HotRoomGateArgs,
  prometheusQueries,
} from './lib/phase8HotRoomReleaseGatePlan.mjs';

async function main() {
  const options = parsePhase8HotRoomGateArgs(process.argv.slice(2));
  const loadSummary = await runLoadChat(options);
  assertLoadSummary(loadSummary, options);
  const prometheusSnapshot = await readPrometheusSnapshot(options.prometheusUrl);
  assertPrometheusSnapshot(prometheusSnapshot, options);

  console.log(JSON.stringify({
    ok: true,
    loadSummary,
    prometheusSnapshot,
    thresholds: {
      minStreamShardCount: options.minStreamShardCount,
      maxFanoutP95Ms: options.maxFanoutP95Ms,
      maxStreamGroupLagEntries: options.maxStreamGroupLagEntries,
    },
  }, null, 2));
}

async function runLoadChat(options) {
  const args = buildLoadChatArgs(options);
  const output = await runProcess(process.execPath, args);
  return JSON.parse(output);
}

function runProcess(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (chunk) => {
      stdout += chunk.toString('utf8');
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk.toString('utf8');
    });
    child.on('error', reject);
    child.on('close', (code) => {
      if (code !== 0) {
        reject(new Error(`${command} ${args.join(' ')} failed with code ${code}: ${stderr.trim()}`));
        return;
      }
      resolve(stdout.trim());
    });
  });
}

async function readPrometheusSnapshot(prometheusUrl) {
  return {
    fanoutP95Seconds: await queryPrometheusNumber(prometheusUrl, prometheusQueries.fanoutP95Seconds),
    observedStreamShardCount: await queryPrometheusNumber(prometheusUrl, prometheusQueries.observedStreamShardCount),
    maxStreamGroupLagEntries: await queryPrometheusNumber(prometheusUrl, prometheusQueries.maxStreamGroupLagEntries),
  };
}

async function queryPrometheusNumber(prometheusUrl, query) {
  const url = new URL('/api/v1/query', prometheusUrl);
  url.searchParams.set('query', query);
  const response = await fetch(url);
  const body = await response.json();
  if (!response.ok || body.status !== 'success') {
    throw new Error(`Prometheus query failed: ${query}`);
  }
  const result = body.data?.result?.[0]?.value?.[1];
  const value = Number(result ?? 0);
  if (!Number.isFinite(value)) {
    throw new Error(`Prometheus query returned non-numeric value: ${query}`);
  }
  return value;
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
