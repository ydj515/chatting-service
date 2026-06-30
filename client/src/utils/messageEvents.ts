import type { Message, WebSocketMessage } from '@/types/index.ts';

const DEFAULT_LIVE_FEED_MAX_MESSAGES = 1000;
const DEFAULT_LIVE_FEED_MAX_AGE_SECONDS = 60;

interface LiveFeedPolicy {
  maxMessages?: number;
  maxAgeSeconds?: number;
}

const messageKey = (message: Message): string => {
  return message.messageId ?? `legacy:${message.id}`;
};

export const messageRenderKey = (message: Message): string => {
  return messageKey(message);
};

const eventTimestampToIso = (timestamp: string | number | undefined): string => {
  if (typeof timestamp === 'number') {
    return new Date(timestamp).toISOString();
  }

  if (typeof timestamp === 'string' && timestamp.trim().length > 0) {
    return new Date(timestamp).toISOString();
  }

  return new Date().toISOString();
};

const eventToMessage = (event: WebSocketMessage): Message | null => {
  if (
    event.type !== 'CHAT_MESSAGE' ||
    event.id == null ||
    event.chatRoomId == null ||
    event.senderId == null ||
    event.messageType == null ||
    typeof event.content !== 'string'
  ) {
    return null;
  }

  const sequenceNumber = event.sequenceNumber ?? event.roomSeq ?? 0;
  const roomSeq = event.roomSeq ?? sequenceNumber;
  const senderName = event.senderName ?? `user-${event.senderId}`;

  return {
    id: event.id,
    messageId: event.messageId ?? `legacy:${event.id}`,
    clientMessageId: event.clientMessageId,
    chatRoomId: event.chatRoomId,
    sender: {
      id: event.senderId,
      username: senderName,
      displayName: senderName,
      isActive: true,
      createdAt: eventTimestampToIso(event.timestamp),
    },
    type: event.messageType,
    content: event.content,
    sequenceNumber,
    roomSeq,
    streamShard: event.streamShard ?? 0,
    writeShard: event.writeShard ?? 0,
    fanoutShard: event.fanoutShard ?? 0,
    isEdited: false,
    isDeleted: false,
    createdAt: eventTimestampToIso(event.timestamp),
  };
};

export const sortMessagesForDisplay = (messages: Message[]): Message[] => {
  return [...messages].sort((a, b) => {
    const aSeq = a.roomSeq ?? a.sequenceNumber ?? 0;
    const bSeq = b.roomSeq ?? b.sequenceNumber ?? 0;
    if (aSeq !== bSeq) {
      return aSeq - bSeq;
    }

    return new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
  });
};

export const boundedLiveFeedMessages = (
  messages: Message[],
  policy: LiveFeedPolicy = {},
): Message[] => {
  const sorted = sortMessagesForDisplay(messages);
  if (sorted.length === 0) {
    return sorted;
  }

  const maxMessages = Math.max(1, policy.maxMessages ?? DEFAULT_LIVE_FEED_MAX_MESSAGES);
  const maxAgeSeconds = Math.max(1, policy.maxAgeSeconds ?? DEFAULT_LIVE_FEED_MAX_AGE_SECONDS);
  const latestTimestamp = sorted.reduce((latest, message) => {
    const timestamp = new Date(message.createdAt).getTime();
    return timestamp > latest ? timestamp : latest;
  }, 0);
  const cutoffTimestamp = latestTimestamp - maxAgeSeconds * 1000;
  const ageBounded = sorted.filter((message) => new Date(message.createdAt).getTime() >= cutoffTimestamp);

  return ageBounded.slice(-maxMessages);
};

export const mergeMessages = (previous: Message[], incoming: Message[]): Message[] => {
  const byMessageId = new Map<string, Message>();
  previous.forEach((message) => {
    byMessageId.set(messageKey(message), message);
  });

  incoming.forEach((message) => {
    byMessageId.set(messageKey(message), message);
  });

  return boundedLiveFeedMessages([...byMessageId.values()]);
};

export const applyWebSocketMessageEvent = (
  previous: Message[],
  event: WebSocketMessage,
  currentRoomId: number,
): Message[] => {
  if (event.type === 'MESSAGE_ACCEPTED') {
    return previous;
  }

  if (event.type === 'CHAT_MESSAGE_BATCH') {
    const incoming = (event.messages ?? [])
      .map(eventToMessage)
      .filter((message): message is Message => message !== null)
      .filter((message) => message.chatRoomId === currentRoomId);

    return incoming.length > 0 ? mergeMessages(previous, incoming) : previous;
  }

  const message = eventToMessage(event);
  if (message === null || message.chatRoomId !== currentRoomId) {
    return previous;
  }

  return mergeMessages(previous, [message]);
};

export const createClientMessageId = (): string => {
  const random = crypto.getRandomValues(new Uint8Array(16));
  const randomPart = Array.from(random)
    .map((value) => value.toString(16).padStart(2, '0'))
    .join('');
  return `client:${Date.now().toString(36)}:${randomPart}`;
};
