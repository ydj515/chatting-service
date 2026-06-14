import crypto from 'node:crypto';
import net from 'node:net';
import tls from 'node:tls';
import { URL } from 'node:url';

const httpBaseUrl = process.env.CHAT_HTTP_URL ?? 'http://localhost/api';
const wsBaseUrl = process.env.CHAT_WS_URL ?? 'ws://localhost/api/ws/chat';
const timeoutMs = Number(process.env.CHAT_VERIFY_TIMEOUT_MS ?? 15000);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function redactSensitiveUrl(value) {
  const url = new URL(value.toString());
  for (const name of ['ticket', 'token']) {
    if (url.searchParams.has(name)) {
      url.searchParams.set(name, '[redacted]');
    }
  }
  return url.toString();
}

async function requestJson(path, options = {}) {
  const base = new URL(httpBaseUrl);
  const basePath = base.pathname.endsWith('/') ? base.pathname : `${base.pathname}/`;
  const normalizedPath = path.startsWith('/') ? path.slice(1) : path;
  const url = new URL(`${basePath}${normalizedPath}`, base.origin);
  const response = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers ?? {}),
    },
  });

  const text = await response.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch (error) {
      throw new Error(`${options.method ?? 'GET'} ${url} returned non-JSON response: ${text.slice(0, 120)}`);
    }
  }

  if (!response.ok) {
    throw new Error(`${options.method ?? 'GET'} ${url} failed: ${response.status} ${text}`);
  }

  return body;
}

async function registerUser(prefix) {
  const suffix = `${Date.now().toString(36)}${Math.floor(Math.random() * 1000).toString(36)}`;
  const username = `${prefix}_${suffix}`;
  const password = 'password';
  const user = await requestJson('/users/register', {
    method: 'POST',
    body: JSON.stringify({
      username,
      password,
      displayName: username,
    }),
  });
  return { ...user, password };
}

async function loginUser(user) {
  return requestJson('/users/login', {
    method: 'POST',
    body: JSON.stringify({
      username: user.username,
      password: user.password,
    }),
  });
}

const authorizationHeaders = (login) => ({
  Authorization: `${login.tokenType} ${login.sessionToken}`,
});

async function createRoom(namePrefix, login) {
  return requestJson('/chat-rooms', {
    method: 'POST',
    headers: authorizationHeaders(login),
    body: JSON.stringify({
      name: `${namePrefix}-${Date.now()}`,
      description: 'chat verification room',
      type: 'GROUP',
      imageUrl: null,
      maxMembers: 10,
    }),
  });
}

async function issueWebSocketTicket(login) {
  return requestJson('/ws-tickets', {
    method: 'POST',
    headers: authorizationHeaders(login),
  });
}

async function assertUserIdOnlyRestRejected(userId) {
  try {
    await requestJson(`/chat-rooms?userId=${userId}&page=0&size=1`);
    throw new Error('REST API accepted a userId-only request');
  } catch (error) {
    if (!String(error.message).includes('failed: 400')) {
      throw error;
    }
  }
}

class RawWebSocket {
  constructor(url) {
    this.url = new URL(url);
    this.socket = null;
    this.buffer = Buffer.alloc(0);
    this.waiters = [];
  }

  connect() {
    return new Promise((resolve, reject) => {
      const isSecure = this.url.protocol === 'wss:';
      const port = Number(this.url.port || (isSecure ? 443 : 80));
      const host = this.url.hostname;
      const key = crypto.randomBytes(16).toString('base64');
      const path = `${this.url.pathname}${this.url.search}`;
      const socketFactory = isSecure ? tls.connect : net.createConnection;

      this.socket = socketFactory({ host, port }, () => {
        this.socket.write([
          `GET ${path} HTTP/1.1`,
          `Host: ${this.url.host}`,
          'Upgrade: websocket',
          'Connection: Upgrade',
          `Sec-WebSocket-Key: ${key}`,
          'Sec-WebSocket-Version: 13',
          '',
          '',
        ].join('\r\n'));
      });

      let handshakeBuffer = Buffer.alloc(0);
      let handshakeDone = false;

      const timer = setTimeout(() => {
        reject(new Error(`WebSocket handshake timeout: ${redactSensitiveUrl(this.url)}`));
        this.close();
      }, timeoutMs);

      this.socket.on('data', (chunk) => {
        if (!handshakeDone) {
          handshakeBuffer = Buffer.concat([handshakeBuffer, chunk]);
          const headerEnd = handshakeBuffer.indexOf('\r\n\r\n');
          if (headerEnd === -1) {
            return;
          }

          const header = handshakeBuffer.subarray(0, headerEnd).toString('utf8');
          if (!header.startsWith('HTTP/1.1 101') && !header.startsWith('HTTP/1.0 101')) {
            const body = handshakeBuffer.subarray(headerEnd + 4).toString('utf8').trim();
            const bodySummary = body ? ` body=${body.slice(0, 160)}` : '';
            clearTimeout(timer);
            reject(new Error(`WebSocket handshake failed: ${header.split('\r\n')[0]}${bodySummary}`));
            this.close();
            return;
          }

          handshakeDone = true;
          clearTimeout(timer);
          resolve();

          const rest = handshakeBuffer.subarray(headerEnd + 4);
          if (rest.length > 0) {
            this.readFrames(rest);
          }
          return;
        }

        this.readFrames(chunk);
      });

      this.socket.on('error', (error) => {
        clearTimeout(timer);
        reject(error);
        this.rejectWaiters(error);
      });

      this.socket.on('close', () => {
        this.rejectWaiters(new Error(`WebSocket closed: ${redactSensitiveUrl(this.url)}`));
      });
    });
  }

  readFrames(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);

    while (this.buffer.length >= 2) {
      const first = this.buffer[0];
      const second = this.buffer[1];
      const opcode = first & 0x0f;
      const masked = (second & 0x80) !== 0;
      let length = second & 0x7f;
      let offset = 2;

      if (length === 126) {
        if (this.buffer.length < offset + 2) return;
        length = this.buffer.readUInt16BE(offset);
        offset += 2;
      } else if (length === 127) {
        if (this.buffer.length < offset + 8) return;
        const bigLength = this.buffer.readBigUInt64BE(offset);
        if (bigLength > BigInt(Number.MAX_SAFE_INTEGER)) {
          throw new Error('WebSocket frame is too large');
        }
        length = Number(bigLength);
        offset += 8;
      }

      const maskOffset = masked ? 4 : 0;
      if (this.buffer.length < offset + maskOffset + length) return;

      const mask = masked ? this.buffer.subarray(offset, offset + 4) : null;
      offset += maskOffset;
      let payload = this.buffer.subarray(offset, offset + length);
      this.buffer = this.buffer.subarray(offset + length);

      if (mask) {
        payload = Buffer.from(payload.map((byte, index) => byte ^ mask[index % 4]));
      }

      if (opcode === 0x1) {
        this.resolveMessage(payload.toString('utf8'));
      } else if (opcode === 0x8) {
        this.close();
      } else if (opcode === 0x9) {
        this.writeFrame(0xA, payload);
      }
    }
  }

  sendJson(payload) {
    this.writeFrame(0x1, Buffer.from(JSON.stringify(payload)));
  }

  writeFrame(opcode, payload) {
    const mask = crypto.randomBytes(4);
    const length = payload.length;
    let header;

    if (length < 126) {
      header = Buffer.from([0x80 | opcode, 0x80 | length]);
    } else if (length < 65536) {
      header = Buffer.alloc(4);
      header[0] = 0x80 | opcode;
      header[1] = 0x80 | 126;
      header.writeUInt16BE(length, 2);
    } else {
      header = Buffer.alloc(10);
      header[0] = 0x80 | opcode;
      header[1] = 0x80 | 127;
      header.writeBigUInt64BE(BigInt(length), 2);
    }

    const maskedPayload = Buffer.from(payload.map((byte, index) => byte ^ mask[index % 4]));
    this.socket.write(Buffer.concat([header, mask, maskedPayload]));
  }

  waitForJson(predicate) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(`Timed out waiting for WebSocket message: ${redactSensitiveUrl(this.url)}`));
      }, timeoutMs);

      this.waiters.push({
        resolve: (message) => {
          clearTimeout(timer);
          resolve(message);
        },
        reject: (error) => {
          clearTimeout(timer);
          reject(error);
        },
        predicate,
      });
    });
  }

  resolveMessage(rawMessage) {
    let message;
    try {
      message = JSON.parse(rawMessage);
    } catch {
      return;
    }

    const waiterIndex = this.waiters.findIndex((waiter) => waiter.predicate(message));
    if (waiterIndex !== -1) {
      const [waiter] = this.waiters.splice(waiterIndex, 1);
      waiter.resolve(message);
    }
  }

  rejectWaiters(error) {
    const waiters = this.waiters.splice(0);
    waiters.forEach((waiter) => waiter.reject(error));
  }

  close() {
    if (this.socket && !this.socket.destroyed) {
      this.socket.destroy();
    }
  }
}

function findChatMessage(message, predicate) {
  if (message.type === 'CHAT_MESSAGE') {
    return predicate(message) ? message : null;
  }

  if (message.type === 'CHAT_MESSAGE_BATCH' && Array.isArray(message.messages)) {
    return message.messages.find((item) => predicate(item)) ?? null;
  }

  return null;
}

async function connectTicket(ticket, userIdQuery = null, label = 'session') {
  const url = new URL(wsBaseUrl);
  url.searchParams.set('ticket', ticket);
  if (userIdQuery !== null) {
    url.searchParams.set('userId', String(userIdQuery));
  }
  const ws = new RawWebSocket(url.toString());
  try {
    await ws.connect();
  } catch (error) {
    throw new Error(`${label} WebSocket connection failed: ${error.message}`);
  }
  return ws;
}

async function connectSession(login, userIdQuery = null, label = 'session') {
  const ticket = await issueWebSocketTicket(login);
  return connectTicket(ticket.ticket, userIdQuery, label);
}

async function assertTicketHandshakeRejected(ticket, label) {
  const url = new URL(wsBaseUrl);
  url.searchParams.set('ticket', ticket);
  const ws = new RawWebSocket(url.toString());
  try {
    await ws.connect();
    throw new Error(`WebSocket accepted ${label}`);
  } catch (error) {
    if (!String(error.message).includes('WebSocket handshake failed')) {
      throw error;
    }
  } finally {
    ws.close();
  }
}

async function assertSessionTokenHandshakeRejected(sessionToken) {
  const url = new URL(wsBaseUrl);
  url.searchParams.set('token', sessionToken);
  const ws = new RawWebSocket(url.toString());
  try {
    await ws.connect();
    throw new Error('WebSocket accepted session token query fallback');
  } catch (error) {
    if (!String(error.message).includes('WebSocket handshake failed')) {
      throw error;
    }
  } finally {
    ws.close();
  }
}

async function assertUserIdOnlyHandshakeRejected(userId) {
  const url = new URL(wsBaseUrl);
  url.searchParams.set('userId', String(userId));
  const ws = new RawWebSocket(url.toString());
  try {
    await ws.connect();
    throw new Error('WebSocket accepted a userId-only handshake');
  } catch (error) {
    if (!String(error.message).includes('WebSocket handshake failed')) {
      throw error;
    }
  } finally {
    ws.close();
  }
}

async function main() {
  const sender = await registerUser('vs');
  const receiver = await registerUser('vr');
  const senderLogin = await loginUser(sender);
  const receiverLogin = await loginUser(receiver);
  const room = await createRoom('verify-room', senderLogin);

  await assertUserIdOnlyRestRejected(sender.id);
  await assertUserIdOnlyHandshakeRejected(sender.id);
  await assertSessionTokenHandshakeRejected(senderLogin.sessionToken);

  await requestJson(`/chat-rooms/${room.id}/members`, {
    method: 'POST',
    headers: authorizationHeaders(receiverLogin),
  });

  const senderTicket = await issueWebSocketTicket(senderLogin);
  const senderWs = await connectTicket(senderTicket.ticket, null, 'sender');
  await assertTicketHandshakeRejected(senderTicket.ticket, 'a reused ticket');
  const receiverWs = await connectSession(receiverLogin, null, 'receiver');

  try {
    await sleep(1000);

    const content = `verify-message-${Date.now()}`;
    const clientMessageId = `verify-client-${Date.now()}`;
    const acceptedPromise = senderWs.waitForJson((message) => (
      message.type === 'MESSAGE_ACCEPTED' &&
      message.chatRoomId === room.id &&
      message.clientMessageId === clientMessageId
    ));
    const receivedPromise = receiverWs.waitForJson((message) => findChatMessage(message, (item) => (
      item.chatRoomId === room.id &&
      item.senderId === sender.id &&
      item.content === content &&
      item.clientMessageId === clientMessageId
    )));

    senderWs.sendJson({
      type: 'SEND_MESSAGE',
      chatRoomId: room.id,
      messageType: 'TEXT',
      content,
      clientMessageId,
    });

    const accepted = await acceptedPromise;
    const received = findChatMessage(await receivedPromise, (item) => (
      item.chatRoomId === room.id &&
      item.senderId === sender.id &&
      item.content === content &&
      item.clientMessageId === clientMessageId
    ));
    if (!received) {
      throw new Error('Matched WebSocket payload did not contain the expected chat message');
    }
    if (
      !accepted.messageId ||
      accepted.messageId !== received.messageId ||
      accepted.roomSeq !== received.roomSeq ||
      accepted.sequenceNumber !== received.sequenceNumber
    ) {
      throw new Error('MESSAGE_ACCEPTED and CHAT_MESSAGE have inconsistent message contract fields');
    }

    const history = await requestJson(`/chat-rooms/${room.id}/messages?page=0&size=10`, {
      headers: authorizationHeaders(receiverLogin),
    });
    const savedMessages = history.content.filter((message) => (
      message.messageId === received.messageId &&
      message.clientMessageId === clientMessageId &&
      message.roomSeq === received.roomSeq &&
      message.content === content
    ));

    if (savedMessages.length !== 1) {
      throw new Error('WebSocket message was received but not found in message history');
    }

    const duplicateAcceptedPromise = senderWs.waitForJson((message) => (
      message.type === 'MESSAGE_ACCEPTED' &&
      message.chatRoomId === room.id &&
      message.clientMessageId === clientMessageId
    ));
    senderWs.sendJson({
      type: 'SEND_MESSAGE',
      chatRoomId: room.id,
      messageType: 'TEXT',
      content,
      clientMessageId,
    });
    const duplicateAccepted = await duplicateAcceptedPromise;
    if (
      duplicateAccepted.messageId !== accepted.messageId ||
      duplicateAccepted.roomSeq !== accepted.roomSeq
    ) {
      throw new Error('Duplicate clientMessageId did not return the original message contract');
    }

    const historyAfterDuplicate = await requestJson(`/chat-rooms/${room.id}/messages?page=0&size=10`, {
      headers: authorizationHeaders(receiverLogin),
    });
    const duplicateSavedMessages = historyAfterDuplicate.content.filter((message) => (
      message.clientMessageId === clientMessageId
    ));
    if (duplicateSavedMessages.length !== 1) {
      throw new Error('Duplicate clientMessageId created more than one saved message');
    }

    const spoofedUserIdContent = `verify-spoofed-user-id-message-${Date.now()}`;
    const spoofedClientMessageId = `verify-spoofed-client-${Date.now()}`;
    const spoofedUserIdReceivedPromise = receiverWs.waitForJson((message) => findChatMessage(message, (item) => (
      item.chatRoomId === room.id &&
      item.senderId === sender.id &&
      item.content === spoofedUserIdContent &&
      item.clientMessageId === spoofedClientMessageId
    )));
    const spoofedUserIdWs = await connectSession(senderLogin, receiver.id, 'spoofed-user-id');
    try {
      spoofedUserIdWs.sendJson({
        type: 'SEND_MESSAGE',
        chatRoomId: room.id,
        messageType: 'TEXT',
        content: spoofedUserIdContent,
        clientMessageId: spoofedClientMessageId,
      });
      await spoofedUserIdReceivedPromise;
    } finally {
      spoofedUserIdWs.close();
    }

    const lateRoom = await createRoom('verify-late-join-room', senderLogin);
    await requestJson(`/chat-rooms/${lateRoom.id}/members`, {
      method: 'POST',
      headers: authorizationHeaders(receiverLogin),
    });
    await sleep(1000);

    const lateJoinContent = `verify-late-join-message-${Date.now()}`;
    const lateJoinClientMessageId = `verify-late-join-client-${Date.now()}`;
    const lateJoinReceivedPromise = receiverWs.waitForJson((message) => findChatMessage(message, (item) => (
      item.chatRoomId === lateRoom.id &&
      item.senderId === sender.id &&
      item.content === lateJoinContent &&
      item.clientMessageId === lateJoinClientMessageId
    )));

    senderWs.sendJson({
      type: 'SEND_MESSAGE',
      chatRoomId: lateRoom.id,
      messageType: 'TEXT',
      content: lateJoinContent,
      clientMessageId: lateJoinClientMessageId,
    });

    const lateJoinReceived = findChatMessage(await lateJoinReceivedPromise, (item) => (
      item.chatRoomId === lateRoom.id &&
      item.senderId === sender.id &&
      item.content === lateJoinContent &&
      item.clientMessageId === lateJoinClientMessageId
    ));
    if (!lateJoinReceived) {
      throw new Error('Matched late-join payload did not contain the expected chat message');
    }

    console.log(JSON.stringify({
      ok: true,
      roomId: room.id,
      lateJoinRoomId: lateRoom.id,
      senderId: sender.id,
      receiverId: receiver.id,
      messageId: received.id,
      serverMessageId: received.messageId,
      clientMessageId,
      roomSeq: received.roomSeq,
      spoofedUserIdAcceptedAs: sender.id,
      lateJoinMessageId: lateJoinReceived.id,
      lateJoinServerMessageId: lateJoinReceived.messageId,
    }, null, 2));
  } finally {
    senderWs.close();
    receiverWs.close();
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
