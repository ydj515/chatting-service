import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parsePhase8HotRoomGateArgs } from './phase8HotRoomReleaseGatePlan.mjs';
import { runReleaseGate } from './phase8HotRoomReleaseGateRunner.mjs';

test('runReleaseGate executes staged gates in order', async () => {
  const options = parsePhase8HotRoomGateArgs(['--stages', '1000,5000']);
  const calls = [];

  const result = await runReleaseGate(options, {
    runLoadChat: async (stage) => {
      calls.push(stage.name);
      return {
        ok: true,
        sent: stage.messagesPerSec * stage.durationSeconds,
        sender: {
          accepted: stage.messagesPerSec * stage.durationSeconds,
          sendElapsedMillis: stage.durationSeconds * 1000,
        },
        viewers: stage.viewers,
        receivedPerViewer: Array.from(
          { length: stage.viewers },
          () => stage.messagesPerSec * stage.durationSeconds,
        ),
      };
    },
    readPrometheusSnapshot: async () => ({
      fanoutP95Seconds: 0.1,
      observedStreamShardCount: 16,
      maxStreamGroupLagEntries: 0,
    }),
  });

  assert.deepEqual(calls, ['1k', '5k']);
  assert.equal(result.ok, true);
  assert.equal(result.lastPassedStage, '5k');
});

test('runReleaseGate stops after the first failing stage', async () => {
  const options = parsePhase8HotRoomGateArgs(['--stages', '1000,3000,5000,7000,10000']);
  const calls = [];

  const result = await runReleaseGate(options, {
    runLoadChat: async (stage) => {
      calls.push(stage.name);
      if (stage.name === '5k') {
        throw new Error('load runner failed at 5k');
      }
      return {
        ok: true,
        sent: stage.messagesPerSec * stage.durationSeconds,
        sender: {
          accepted: stage.messagesPerSec * stage.durationSeconds,
          sendElapsedMillis: stage.durationSeconds * 1000,
        },
        viewers: stage.viewers,
        receivedPerViewer: Array.from(
          { length: stage.viewers },
          () => stage.messagesPerSec * stage.durationSeconds,
        ),
      };
    },
    readPrometheusSnapshot: async () => ({
      fanoutP95Seconds: 0.1,
      observedStreamShardCount: 16,
      maxStreamGroupLagEntries: 0,
    }),
  });

  assert.deepEqual(calls, ['1k', '3k', '5k']);
  assert.equal(result.ok, false);
  assert.equal(result.lastPassedStage, '3k');
  assert.equal(result.failedStage, '5k');
});
