import crypto from 'node:crypto';
import net from 'node:net';
import tls from 'node:tls';
import { URL } from 'node:url';

const httpBaseUrl = process.env.CHAT_HTTP_URL ?? 'http://localhost/api';
const wsBaseUrl = process.env.CHAT_WS_URL ?? 'ws://localhost/api/ws/chat';
const timeoutMs = Number(process.env.CHAT_VERIFY_TIMEOUT_MS ?? 15000);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

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
  return requestJson('/users/register', {
    method: 'POST',
    body: JSON.stringify({
      username,
      password: 'password',
      displayName: username,
    }),
  });
}

async function createRoom(createdBy, namePrefix) {
  return requestJson(`/chat-rooms?createdBy=${createdBy}`, {
    method: 'POST',
    body: JSON.stringify({
      name: `${namePrefix}-${Date.now()}`,
      description: 'chat verification room',
      type: 'GROUP',
      imageUrl: null,
      maxMembers: 10,
    }),
  });
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
        reject(new Error(`WebSocket handshake timeout: ${this.url}`));
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
            clearTimeout(timer);
            reject(new Error(`WebSocket handshake failed: ${header.split('\r\n')[0]}`));
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
        this.rejectWaiters(new Error(`WebSocket closed: ${this.url}`));
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
        reject(new Error(`Timed out waiting for WebSocket message: ${this.url}`));
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

async function connectUser(userId) {
  const url = new URL(wsBaseUrl);
  url.searchParams.set('userId', String(userId));
  const ws = new RawWebSocket(url.toString());
  await ws.connect();
  return ws;
}

async function main() {
  const sender = await registerUser('vs');
  const receiver = await registerUser('vr');
  const room = await createRoom(sender.id, 'verify-room');

  await requestJson(`/chat-rooms/${room.id}/members`, {
    method: 'POST',
    body: JSON.stringify({ userId: receiver.id }),
  });

  const senderWs = await connectUser(sender.id);
  const receiverWs = await connectUser(receiver.id);

  try {
    await sleep(1000);

    const content = `verify-message-${Date.now()}`;
    const receivedPromise = receiverWs.waitForJson((message) => (
      message.chatRoomId === room.id &&
      message.senderId === sender.id &&
      message.content === content
    ));

    senderWs.sendJson({
      type: 'SEND_MESSAGE',
      chatRoomId: room.id,
      messageType: 'TEXT',
      content,
    });

    const received = await receivedPromise;
    const history = await requestJson(`/chat-rooms/${room.id}/messages?userId=${receiver.id}&page=0&size=10`);
    const saved = history.content.some((message) => message.id === received.id && message.content === content);

    if (!saved) {
      throw new Error('WebSocket message was received but not found in message history');
    }

    const lateRoom = await createRoom(sender.id, 'verify-late-join-room');
    await requestJson(`/chat-rooms/${lateRoom.id}/members`, {
      method: 'POST',
      body: JSON.stringify({ userId: receiver.id }),
    });
    await sleep(1000);

    const lateJoinContent = `verify-late-join-message-${Date.now()}`;
    const lateJoinReceivedPromise = receiverWs.waitForJson((message) => (
      message.chatRoomId === lateRoom.id &&
      message.senderId === sender.id &&
      message.content === lateJoinContent
    ));

    senderWs.sendJson({
      type: 'SEND_MESSAGE',
      chatRoomId: lateRoom.id,
      messageType: 'TEXT',
      content: lateJoinContent,
    });

    const lateJoinReceived = await lateJoinReceivedPromise;

    console.log(JSON.stringify({
      ok: true,
      roomId: room.id,
      lateJoinRoomId: lateRoom.id,
      senderId: sender.id,
      receiverId: receiver.id,
      messageId: received.id,
      lateJoinMessageId: lateJoinReceived.id,
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
