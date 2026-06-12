import test from 'node:test';
import assert from 'node:assert/strict';
import { buildWebSocketTicketUrl } from './webSocketTicket.ts';

test('WebSocket URL은 session token이 아니라 one-time ticket query를 사용한다', () => {
  const url = buildWebSocketTicketUrl('ws://localhost/api/ws/chat?token=session-token', 'ticket-value');

  assert.equal(url, 'ws://localhost/api/ws/chat?ticket=ticket-value');
});
