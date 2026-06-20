import assert from 'node:assert/strict';
import { test } from 'node:test';
import { RawWebSocketFrameDecoder } from './rawWebSocketFrameDecoder.mjs';

test('RawWebSocketFrameDecoder decodes an unfragmented text frame', () => {
  const messages = [];
  const decoder = new RawWebSocketFrameDecoder({ onText: (text) => messages.push(text) });

  decoder.read(frame({ opcode: 0x1, payload: 'hello' }));

  assert.deepEqual(messages, ['hello']);
});

test('RawWebSocketFrameDecoder joins fragmented text continuation frames', () => {
  const messages = [];
  const decoder = new RawWebSocketFrameDecoder({ onText: (text) => messages.push(text) });

  decoder.read(Buffer.concat([
    frame({ opcode: 0x1, payload: '{"type":"CHAT_', fin: false }),
    frame({ opcode: 0x0, payload: 'MESSAGE_BATCH"}', fin: true }),
  ]));

  assert.deepEqual(messages, ['{"type":"CHAT_MESSAGE_BATCH"}']);
});

test('RawWebSocketFrameDecoder keeps partial frame data until the next chunk arrives', () => {
  const messages = [];
  const decoder = new RawWebSocketFrameDecoder({ onText: (text) => messages.push(text) });
  const encoded = frame({ opcode: 0x1, payload: 'hello' });

  decoder.read(encoded.subarray(0, 3));
  decoder.read(encoded.subarray(3));

  assert.deepEqual(messages, ['hello']);
});

test('RawWebSocketFrameDecoder rejects frames above the payload size ceiling', () => {
  const decoder = new RawWebSocketFrameDecoder({
    maxPayloadBytes: 1024,
  });

  assert.throws(
    () => decoder.read(extendedLengthHeader({ opcode: 0x1, length: 1025 })),
    /payload too large/,
  );
});

function frame({ opcode, payload, fin = true }) {
  const body = Buffer.from(payload);
  if (body.length >= 126) {
    throw new Error('test frame helper only supports small payloads');
  }
  return Buffer.concat([
    Buffer.from([(fin ? 0x80 : 0) | opcode, body.length]),
    body,
  ]);
}

function extendedLengthHeader({ opcode, length, fin = true }) {
  const header = Buffer.alloc(4);
  header[0] = (fin ? 0x80 : 0) | opcode;
  header[1] = 126;
  header.writeUInt16BE(length, 2);
  return header;
}
