#!/usr/bin/env node
import { execFileSync, spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  buildLoadChatArgs,
  findOwnerContainer,
  parseDockerInspectRows,
  parseLoadChatJson,
  parseTakeoverSmokeArgs,
  parseWorkerContainerIds,
  redisOwnerScanPattern,
} from './lib/phase6TakeoverSmokePlan.mjs';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function main() {
  const options = parseTakeoverSmokeArgs(process.argv.slice(2));
  const env = {
    ...process.env,
    CHAT_ADMIN_TOKEN: process.env.CHAT_ADMIN_TOKEN ?? 'test',
  };
  const metadataFile = path.join(os.tmpdir(), `phase6-load-${Date.now()}-${process.pid}.json`);
  const workerIds = parseWorkerContainerIds(runCaptured('docker', ['compose', 'ps', '-q', options.service], { env }));
  const workerRows = parseDockerInspectRows(
    runCaptured('docker', [
      'inspect',
      '--format',
      '{{.Id}}|{{.Config.Hostname}}|{{.Name}}',
      ...workerIds,
    ], { env }),
  );
  const loadScript = fileURLToPath(new URL('./load-chat.mjs', import.meta.url));
  const loadArgs = [
    loadScript,
    ...buildLoadChatArgs(options),
    '--metadata-file',
    metadataFile,
  ];

  let killedContainer = null;
  const load = spawn(process.execPath, loadArgs, {
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const loadExit = waitForExit(load);
  let stdout = '';
  let stderr = '';
  load.stdout.on('data', (chunk) => {
    const text = chunk.toString('utf8');
    stdout += text;
    process.stdout.write(text);
  });
  load.stderr.on('data', (chunk) => {
    const text = chunk.toString('utf8');
    stderr += text;
    process.stderr.write(text);
  });

  try {
    const metadata = await waitForMetadata(metadataFile);
    await sleep(options.killAfterSeconds * 1000);
    const owner = await waitForOwnerLease({
      roomId: metadata.roomId,
      keyPrefix: options.ownerLeaseKeyPrefix,
      env,
    });
    const ownerContainer = findOwnerContainer(owner.value, workerRows);
    if (!ownerContainer) {
      throw new Error(`Owner lease ${owner.key}=${owner.value} did not match worker containers`);
    }
    killedContainer = ownerContainer;
    console.error(`Killing fanout owner ${ownerContainer.name} for room ${metadata.roomId}`);
    runCaptured('docker', ['kill', ownerContainer.id], { env });

    const exitCode = await loadExit;
    if (exitCode !== 0) {
      throw new Error(`load-chat exited with ${exitCode}\n${stderr}`);
    }

    const summary = parseLoadChatJson(stdout);
    console.log(JSON.stringify({
      ok: true,
      killedContainer: killedContainer.name,
      killedContainerId: killedContainer.id.slice(0, 12),
      roomId: summary.roomId,
      sent: summary.sent,
      receivedPerViewer: summary.receivedPerViewer,
      minReceivedRatio: summary.minReceivedRatio,
      assertedRoomSeqOrder: summary.assertedRoomSeqOrder,
    }, null, 2));
  } finally {
    await fs.rm(metadataFile, { force: true });
    if (load.exitCode === null) {
      load.kill('SIGTERM');
    }
    if (killedContainer) {
      runCaptured(
        'docker',
        ['compose', 'up', '-d', '--scale', `${options.service}=${options.restoreScale}`, '--no-build'],
        { env },
      );
    }
  }
}

function runCaptured(command, args, { env }) {
  return execFileSync(command, args, {
    env,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

async function waitForMetadata(metadataFile) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try {
      return JSON.parse(await fs.readFile(metadataFile, 'utf8'));
    } catch {
      await sleep(250);
    }
  }
  throw new Error(`Timed out waiting for load-chat metadata file: ${metadataFile}`);
}

async function waitForOwnerLease({ roomId, keyPrefix, env }) {
  const deadline = Date.now() + 20_000;
  const pattern = redisOwnerScanPattern({ roomId, keyPrefix });
  while (Date.now() < deadline) {
    const keys = runCaptured(
      'docker',
      ['compose', 'exec', '-T', 'redis', 'redis-cli', '--raw', '--scan', '--pattern', pattern],
      { env },
    )
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean);
    for (const key of keys) {
      const value = runCaptured(
        'docker',
        ['compose', 'exec', '-T', 'redis', 'redis-cli', '--raw', 'GET', key],
        { env },
      ).trim();
      if (value) {
        return { key, value };
      }
    }
    await sleep(500);
  }
  throw new Error(`Timed out waiting for fanout owner lease matching ${pattern}`);
}

function waitForExit(child) {
  return new Promise((resolve) => {
    child.once('exit', (code) => resolve(code ?? 1));
  });
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
