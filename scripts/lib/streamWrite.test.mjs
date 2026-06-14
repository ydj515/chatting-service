import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { test } from 'node:test';
import { writeChunk } from './streamWrite.mjs';

test('writeChunk resolves immediately when writable accepts the chunk', async () => {
  const stream = {
    writes: [],
    write(chunk) {
      this.writes.push(chunk);
      return true;
    },
  };

  await writeChunk(stream, 'hello');

  assert.deepEqual(stream.writes, ['hello']);
});

test('writeChunk waits for drain when writable applies backpressure', async () => {
  const stream = new EventEmitter();
  const events = [];
  stream.write = (chunk) => {
    events.push(`write:${chunk}`);
    setTimeout(() => {
      events.push('drain');
      stream.emit('drain');
    }, 5);
    return false;
  };

  await writeChunk(stream, 'hello');

  assert.deepEqual(events, ['write:hello', 'drain']);
});
