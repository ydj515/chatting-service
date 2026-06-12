import test from 'node:test';
import assert from 'node:assert/strict';
import { shouldIgnoreWebSocketEvent } from './webSocketLifecycle.ts';

test('현재 소켓이 아닌 이전 소켓 이벤트는 무시한다', () => {
  const currentSocket = {};
  const staleSocket = {};
  const intentionallyClosedSockets = new WeakSet<object>();

  assert.equal(
    shouldIgnoreWebSocketEvent(currentSocket, staleSocket, intentionallyClosedSockets),
    true,
  );
});

test('의도적으로 닫은 소켓의 error 이벤트는 무시한다', () => {
  const socket = {};
  const intentionallyClosedSockets = new WeakSet<object>([socket]);

  assert.equal(
    shouldIgnoreWebSocketEvent(socket, socket, intentionallyClosedSockets),
    true,
  );
});

test('현재 열려 있는 소켓의 이벤트는 처리한다', () => {
  const socket = {};
  const intentionallyClosedSockets = new WeakSet<object>();

  assert.equal(
    shouldIgnoreWebSocketEvent(socket, socket, intentionallyClosedSockets),
    false,
  );
});
