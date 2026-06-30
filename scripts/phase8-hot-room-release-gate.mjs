#!/usr/bin/env node
import { spawn } from 'node:child_process';
import {
  buildLoadChatArgs,
  parsePhase8HotRoomGateArgs,
  prometheusQueryNumber,
  prometheusQueries,
} from './lib/phase8HotRoomReleaseGatePlan.mjs';
import { runReleaseGate } from './lib/phase8HotRoomReleaseGateRunner.mjs';

async function main() {
  const options = parsePhase8HotRoomGateArgs(process.argv.slice(2));
  const result = await runReleaseGate(options, {
    runLoadChat,
    readPrometheusSnapshot,
  });

  console.log(JSON.stringify(result, null, 2));
  if (!result.ok) {
    process.exitCode = 1;
  }
}

async function runLoadChat(stage, options) {
  const args = buildLoadChatArgs(options, stage);
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
        const error = new Error(`${command} ${args.join(' ')} failed with code ${code}`);
        error.stderr = stderr.trim();
        reject(error);
        return;
      }
      resolve(stdout.trim());
    });
  });
}

async function readPrometheusSnapshot(prometheusUrl) {
  const [fanoutP95Seconds, observedStreamShardCount, maxStreamGroupLagEntries] = await Promise.all([
    queryPrometheusNumber(prometheusUrl, prometheusQueries.fanoutP95Seconds),
    queryPrometheusNumber(prometheusUrl, prometheusQueries.observedStreamShardCount),
    queryPrometheusNumber(prometheusUrl, prometheusQueries.maxStreamGroupLagEntries),
  ]);
  return {
    fanoutP95Seconds,
    observedStreamShardCount,
    maxStreamGroupLagEntries,
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
  return prometheusQueryNumber(body, query);
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
