#!/usr/bin/env node
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import net from 'node:net';
import tls from 'node:tls';
import { URL } from 'node:url';
import {
  assertMinimumReceivedCounts,
  assertRoomSeqOrder,
  buildLoadUsername,
  createViewerMessageCollector,
  flattenChatMessages,
  formatLoadStepError,
  parseLoadChatArgs,
  readJsonResponse,
  redactSensitiveUrl,
  summarizeTakeoverDelivery,
} from './lib/loadChatPlan.mjs';
import { RawWebSocketFrameDecoder } from './lib/rawWebSocketFrameDecoder.mjs';

const httpBaseUrl = process.env.CHAT_HTTP_URL ?? 'http://localhost/api';
const wsBaseUrl = process.env.CHAT_WS_URL ?? 'ws://localhost/api/ws/chat';
const timeoutMs = Number(process.env.CHAT_LOAD_TIMEOUT_MS ?? 15000);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function withLoadStep(context, operation) {
  try {
    return await operation();
  } catch (error) {
    throw new Error(formatLoadStepError({ ...context, cause: error }), { cause: error });
  }
}

async function requestJson(path, options = {}, context = {}) {
  const base = new URL(httpBaseUrl);
  const basePath = base.pathname.endsWith('/') ? base.pathname : `${base.pathname}/`;
  const normalizedPath = path.startsWith('/') ? path.slice(1) : path;
  const url = new URL(`${basePath}${normalizedPath}`, base.origin);
  return withLoadStep({
    method: options.method ?? 'GET',
    url,
    ...context,
  }, async () => {
    const response = await fetch(url, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...(options.headers ?? {}),
      },
    });
    return readJsonResponse(response, { method: options.method ?? 'GET', url });
  });
}

async function registerUser(prefix, context = {}) {
  const suffix = `${Date.now().toString(36)}${Math.floor(Math.random() * 100000).toString(36)}`;
  const username = buildLoadUsername(prefix, suffix);
  const password = 'password';
  const user = await requestJson('/users/register', {
    method: 'POST',
    body: JSON.stringify({ username, password, displayName: username }),
  }, context);
  return { ...user, password };
}

async function loginUser(user, context = {}) {
  return requestJson('/users/login', {
    method: 'POST',
    body: JSON.stringify({ username: user.username, password: user.password }),
  }, context);
}

function authorizationHeaders(login) {
  return { Authorization: `${login.tokenType} ${login.sessionToken}` };
}

async function createRoom(namePrefix, login, context = {}) {
  return requestJson('/chat-rooms', {
    method: 'POST',
    headers: authorizationHeaders(login),
    body: JSON.stringify({
      name: `${namePrefix}-${Date.now()}`,
      description: 'phase 6 load verification room',
      type: 'GROUP',
      imageUrl: null,
      maxMembers: 20000,
    }),
  }, context);
}

async function issueWebSocketTicket(login, context = {}) {
  return requestJson('/ws-tickets', {
    method: 'POST',
    headers: authorizationHeaders(login),
  }, context);
}

async function connectSession(login, onJson, context = {}) {
  const ticket = await issueWebSocketTicket(login, { ...context, step: 'issue-ticket' });
  const url = new URL(wsBaseUrl);
  url.searchParams.set('ticket', ticket.ticket);
  const ws = new RawWebSocket(url.toString(), onJson);
  await withLoadStep({
    ...context,
    step: context.step === 'connect-sender' ? 'connect-sender' : 'websocket-handshake',
    method: 'GET',
    url,
  }, () => ws.connect());
  return ws;
}

class RawWebSocket {
  constructor(url, onJson = () => {}) {
    this.url = new URL(url);
    this.onJson = onJson;
    this.socket = null;
    this.decoder = new RawWebSocketFrameDecoder({
      onText: (text) => this.handleText(text),
      onPing: (payload) => this.writeFrame(0xA, payload),
      onClose: () => this.close(),
    });
  }

  connect() {
    return new Promise((resolve, reject) => {
      const isSecure = this.url.protocol === 'wss:';
      const port = Number(this.url.port || (isSecure ? 443 : 80));
      const key = crypto.randomBytes(16).toString('base64');
      const socketFactory = isSecure ? tls.connect : net.createConnection;
      const path = `${this.url.pathname}${this.url.search}`;
      let handshakeDone = false;
      let handshakeBuffer = Buffer.alloc(0);
      const timer = setTimeout(() => {
        reject(new Error(`WebSocket handshake timeout: ${redactSensitiveUrl(this.url)}`));
        this.close();
      }, timeoutMs);

      this.socket = socketFactory({ host: this.url.hostname, port }, () => {
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

      this.socket.on('data', (chunk) => {
        if (!handshakeDone) {
          handshakeBuffer = Buffer.concat([handshakeBuffer, chunk]);
          const headerEnd = handshakeBuffer.indexOf('\r\n\r\n');
          if (headerEnd === -1) return;
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
          if (rest.length > 0) this.decoder.read(rest);
          return;
        }
        this.decoder.read(chunk);
      });

      this.socket.on('error', (error) => {
        clearTimeout(timer);
        reject(error);
      });
    });
  }

  handleText(rawMessage) {
    try {
      this.onJson(JSON.parse(rawMessage));
    } catch {
      // Ignore non-JSON frames.
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

  close() {
    if (this.socket && !this.socket.destroyed) {
      this.socket.destroy();
    }
  }
}

async function main() {
  const options = parseLoadChatArgs(process.argv.slice(2));
  const sender = await registerUser('load_sender', { label: options.label, step: 'register-sender' });
  const senderLogin = await loginUser(sender, { label: options.label, step: 'login-sender' });
  const room = await createRoom(`load-${options.room}`, senderLogin, {
    label: options.label,
    step: 'create-room',
  });
  if (options.metadataFile) {
    await fs.writeFile(
      options.metadataFile,
      `${JSON.stringify({ roomId: room.id, roomName: room.name })}\n`,
      'utf8',
    );
  }
  const retainMessages = options.summaryMode === 'messages'
    || options.assertRoomSeqOrder
    || options.takeoverDeliverySummary;
  const collector = createViewerMessageCollector({ retainMessages });
  const viewers = [];

  for (let index = 0; index < options.viewers; index += 1) {
    const context = { label: options.label, viewerIndex: index };
    const user = await registerUser(`load_viewer_${index}`, { ...context, step: 'register-viewer' });
    const login = await loginUser(user, { ...context, step: 'login-viewer' });
    await requestJson(`/chat-rooms/${room.id}/members`, {
      method: 'POST',
      headers: authorizationHeaders(login),
    }, { ...context, step: 'join-room' });
    collector.addViewer(user.id);
    viewers.push(await connectSession(login, (frame) => {
      const messages = flattenChatMessages(frame).filter((message) => message.chatRoomId === room.id);
      if (messages.length > 0) {
        collector.record(user.id, messages);
      }
    }));
  }

  const senderWs = await connectSession(senderLogin, undefined, {
    label: options.label,
    step: 'connect-sender',
  });
  await sleep(1000);

  const totalMessages = options.messagesPerSec * options.durationSeconds;
  const tickMillis = 100;
  const messagesPerTick = Math.max(1, Math.round(options.messagesPerSec * tickMillis / 1000));
  let sent = 0;
  const startedAt = Date.now();

  while (sent < totalMessages) {
    const tickStart = Date.now();
    for (let index = 0; index < messagesPerTick && sent < totalMessages; index += 1) {
      sent += 1;
      senderWs.sendJson({
        type: 'SEND_MESSAGE',
        chatRoomId: room.id,
        messageType: 'TEXT',
        content: `load-message-${sent}`,
        clientMessageId: `load-${startedAt}-${sent}`,
      });
    }
    const elapsed = Date.now() - tickStart;
    await sleep(Math.max(0, tickMillis - elapsed));
  }

  await sleep(options.drainWaitSeconds * 1000);
  const receivedCounts = collector.receivedCounts();
  assertMinimumReceivedCounts(receivedCounts, sent, options.minReceivedRatio);
  const receivedSamples = collector.receivedSamples();

  if (options.assertRoomSeqOrder) {
    for (const messages of receivedSamples) {
      assertRoomSeqOrder(messages);
    }
  }

  senderWs.close();
  viewers.forEach((viewer) => viewer.close());
  const takeoverDelivery = options.takeoverDeliverySummary
    ? summarizeTakeoverDelivery(receivedSamples, {
      sent,
      minReceivedRatio: options.minReceivedRatio,
    })
    : null;
  const summary = {
    ok: true,
    roomId: room.id,
    sent,
    viewers: options.viewers,
    receivedPerViewer: receivedCounts,
    minReceivedRatio: options.minReceivedRatio,
    assertedRoomSeqOrder: options.assertRoomSeqOrder,
    takeoverDeliverySummary: options.takeoverDeliverySummary,
    summaryMode: options.summaryMode,
    sampleSummary: collector.sampleSummary(),
    ...(takeoverDelivery ? { takeoverDelivery } : {}),
  };
  console.log(JSON.stringify(summary, null, 2));
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
