import test from 'node:test';
import assert from 'node:assert/strict';
import { nextReconnectDelayMs, shouldIgnoreWebSocketEvent } from './webSocketLifecycle.ts';

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

test('재연결 delay는 현재 시도 횟수에 따라 지수 backoff로 계산한다', () => {
  assert.equal(
    nextReconnectDelayMs({
      reconnectAttempts: 0,
      maxReconnectAttempts: 5,
      reconnectBaseDelayMs: 1000,
      reconnectMaxDelayMs: 30000,
    }),
    1000,
  );

  assert.equal(
    nextReconnectDelayMs({
      reconnectAttempts: 2,
      maxReconnectAttempts: 5,
      reconnectBaseDelayMs: 1000,
      reconnectMaxDelayMs: 30000,
    }),
    4000,
  );
});

test('재연결 delay는 최대 delay를 넘지 않는다', () => {
  assert.equal(
    nextReconnectDelayMs({
      reconnectAttempts: 10,
      maxReconnectAttempts: 20,
      reconnectBaseDelayMs: 1000,
      reconnectMaxDelayMs: 30000,
    }),
    30000,
  );
});

test('최대 재연결 시도 횟수에 도달하면 delay를 반환하지 않는다', () => {
  assert.equal(
    nextReconnectDelayMs({
      reconnectAttempts: 5,
      maxReconnectAttempts: 5,
      reconnectBaseDelayMs: 1000,
      reconnectMaxDelayMs: 30000,
    }),
    null,
  );
});
