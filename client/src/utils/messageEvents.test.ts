import { test } from 'vitest';
import assert from 'node:assert/strict';
import { applyWebSocketMessageEvent, boundedLiveFeedMessages, messageRenderKey } from '@/utils/messageEvents.ts';
import type { Message, WebSocketMessage } from '@/types/index.ts';

const existingMessage = (overrides: Partial<Message> = {}): Message => ({
  id: 1,
  messageId: 'msg-1',
  clientMessageId: 'client-1',
  chatRoomId: 10,
  sender: {
    id: 7,
    username: 'sender',
    displayName: 'sender',
    isActive: true,
    createdAt: '2026-06-12T12:00:00',
  },
  type: 'TEXT',
  content: 'existing',
  sequenceNumber: 1,
  roomSeq: 1,
  streamShard: 0,
  writeShard: 0,
  fanoutShard: 0,
  isEdited: false,
  isDeleted: false,
  createdAt: '2026-06-12T12:00:00',
  ...overrides,
});

test('MESSAGE_ACCEPTED ack는 메시지 목록에 추가하지 않는다', () => {
  const previous = [existingMessage()];
  const ack: WebSocketMessage = {
    type: 'MESSAGE_ACCEPTED',
    id: 2,
    messageId: 'msg-2',
    clientMessageId: 'client-2',
    chatRoomId: 10,
    roomId: 10,
    roomSeq: 2,
    sequenceNumber: 2,
    timestamp: '2026-06-12T12:00:01',
  };

  const next = applyWebSocketMessageEvent(previous, ack, 10);

  assert.deepEqual(next, previous);
});

test('CHAT_MESSAGE는 messageId 기준으로 중복 제거하고 roomSeq 기준으로 정렬한다', () => {
  const previous = [existingMessage({ id: 2, messageId: 'msg-2', roomSeq: 2, sequenceNumber: 2 })];
  const event: WebSocketMessage = {
    type: 'CHAT_MESSAGE',
    id: 1,
    messageId: 'msg-1',
    clientMessageId: 'client-1',
    chatRoomId: 10,
    senderId: 7,
    senderName: 'sender',
    messageType: 'TEXT',
    content: 'first',
    sequenceNumber: 1,
    roomSeq: 1,
    streamShard: 0,
    writeShard: 0,
    fanoutShard: 0,
    timestamp: '2026-06-12T12:00:00',
  };

  const once = applyWebSocketMessageEvent(previous, event, 10);
  const twice = applyWebSocketMessageEvent(once, event, 10);

  assert.equal(once.length, 2);
  assert.equal(twice.length, 2);
  assert.deepEqual(once.map((message) => message.messageId), ['msg-1', 'msg-2']);
});

test('CHAT_MESSAGE_BATCH는 현재 방 메시지만 병합한다', () => {
  const previous = [existingMessage()];
  const event: WebSocketMessage = {
    type: 'CHAT_MESSAGE_BATCH',
    chatRoomId: 10,
    timestamp: '2026-06-12T12:00:03',
    messages: [
      {
        type: 'CHAT_MESSAGE',
        id: 3,
        messageId: 'msg-3',
        clientMessageId: 'client-3',
        chatRoomId: 20,
        senderId: 7,
        senderName: 'sender',
        messageType: 'TEXT',
        content: 'other-room',
        sequenceNumber: 3,
        roomSeq: 3,
        streamShard: 0,
        writeShard: 0,
        fanoutShard: 0,
        timestamp: '2026-06-12T12:00:03',
      },
      {
        type: 'CHAT_MESSAGE',
        id: 2,
        messageId: 'msg-2',
        clientMessageId: 'client-2',
        chatRoomId: 10,
        senderId: 7,
        senderName: 'sender',
        messageType: 'TEXT',
        content: 'second',
        sequenceNumber: 2,
        roomSeq: 2,
        streamShard: 0,
        writeShard: 0,
        fanoutShard: 0,
        timestamp: '2026-06-12T12:00:02',
      },
    ],
  };

  const next = applyWebSocketMessageEvent(previous, event, 10);

  assert.equal(next.length, 2);
  assert.deepEqual(next.map((message) => message.messageId), ['msg-1', 'msg-2']);
});

test('렌더 key는 실시간 fanout id가 같아도 messageId를 우선 사용한다', () => {
  const first = existingMessage({ id: 0, messageId: 'msg-live-1' });
  const second = existingMessage({ id: 0, messageId: 'msg-live-2' });

  assert.equal(messageRenderKey(first), 'msg-live-1');
  assert.equal(messageRenderKey(second), 'msg-live-2');
});

test('bounded live feed는 최신 roomSeq 기준 최대 메시지 개수를 유지한다', () => {
  const messages = Array.from({ length: 1005 }, (_, index) =>
    existingMessage({
      id: index + 1,
      messageId: `msg-${index + 1}`,
      roomSeq: index + 1,
      sequenceNumber: index + 1,
      createdAt: `2026-06-12T12:${String(Math.floor(index / 60)).padStart(2, '0')}:${String(index % 60).padStart(2, '0')}`,
    }),
  );

  const bounded = boundedLiveFeedMessages(messages, { maxAgeSeconds: 2000 });

  assert.equal(bounded.length, 1000);
  assert.equal(bounded[0].messageId, 'msg-6');
  assert.equal(bounded.at(-1)?.messageId, 'msg-1005');
});

test('bounded live feed는 최신 메시지 시간 기준 60초보다 오래된 메시지를 제외한다', () => {
  const messages = [
    existingMessage({
      id: 1,
      messageId: 'msg-old',
      roomSeq: 1,
      sequenceNumber: 1,
      createdAt: '2026-06-12T11:58:59',
    }),
    existingMessage({
      id: 2,
      messageId: 'msg-window-start',
      roomSeq: 2,
      sequenceNumber: 2,
      createdAt: '2026-06-12T11:59:00',
    }),
    existingMessage({
      id: 3,
      messageId: 'msg-latest',
      roomSeq: 3,
      sequenceNumber: 3,
      createdAt: '2026-06-12T12:00:00',
    }),
  ];

  const bounded = boundedLiveFeedMessages(messages);

  assert.deepEqual(
    bounded.map((message) => message.messageId),
    ['msg-window-start', 'msg-latest'],
  );
});

test('bounded live feed는 많은 메시지도 spread 없이 최신 시간을 계산한다', () => {
  const messages = Array.from({ length: 20000 }, (_, index) =>
    existingMessage({
      id: index + 1,
      messageId: `msg-${index + 1}`,
      roomSeq: index + 1,
      sequenceNumber: index + 1,
      createdAt: new Date(Date.UTC(2026, 5, 12, 12, 0, index)).toISOString(),
    }),
  );

  const bounded = boundedLiveFeedMessages(messages, {
    maxMessages: 3,
    maxAgeSeconds: 2,
  });

  assert.deepEqual(
    bounded.map((message) => message.messageId),
    ['msg-19998', 'msg-19999', 'msg-20000'],
  );
});
