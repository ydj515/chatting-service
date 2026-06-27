#!/usr/bin/env node
import crypto from 'node:crypto';
import net from 'node:net';
import tls from 'node:tls';
import { URL } from 'node:url';
import { RawWebSocketFrameDecoder } from './lib/rawWebSocketFrameDecoder.mjs';

const httpBaseUrl = process.env.CHAT_HTTP_URL ?? 'http://localhost/api';
const wsBaseUrl = process.env.CHAT_WS_URL ?? 'ws://localhost/api/ws/chat';
const requestTimeoutMs = Number(process.env.CHAT_HEARTBEAT_REQUEST_TIMEOUT_MS ?? 15000);
const heartbeatTimeoutMs = Number(process.env.CHAT_HEARTBEAT_SMOKE_TIMEOUT_MS ?? 65000);

async function requestJson(path, options = {}) {
  const base = new URL(httpBaseUrl);
  const basePath = base.pathname.endsWith('/') ? base.pathname : `${base.pathname}/`;
  const normalizedPath = path.startsWith('/') ? path.slice(1) : path;
  const url = new URL(`${basePath}${normalizedPath}`, base.origin);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), requestTimeoutMs);

  try {
    const response = await fetch(url, {
      ...options,
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        ...(options.headers ?? {}),
      },
    });
    const text = await response.text();
    const body = text ? JSON.parse(text) : null;

    if (!response.ok) {
      throw new Error(`${options.method ?? 'GET'} ${url} failed: ${response.status} ${text}`);
    }

    return body;
  } finally {
    clearTimeout(timeout);
  }
}

async function registerUser() {
  const suffix = `${Date.now().toString(36).slice(-8)}${crypto.randomInt(10000).toString(36)}`;
  const username = `hb_${suffix}`;
  const password = 'password';
  const user = await requestJson('/users/register', {
    method: 'POST',
    body: JSON.stringify({ username, password, displayName: username }),
  });
  return { ...user, password };
}

async function loginUser(user) {
  return requestJson('/users/login', {
    method: 'POST',
    body: JSON.stringify({ username: user.username, password: user.password }),
  });
}

async function issueWebSocketTicket(login) {
  return requestJson('/ws-tickets', {
    method: 'POST',
    headers: { Authorization: `${login.tokenType} ${login.sessionToken}` },
  });
}

function redactSensitiveUrl(value) {
  const url = new URL(value.toString());
  for (const name of ['ticket', 'token']) {
    if (url.searchParams.has(name)) {
      url.searchParams.set(name, '[redacted]');
    }
  }
  return url.toString();
}

class RawWebSocket {
  constructor(url) {
    this.url = new URL(url);
    this.socket = null;
    this.pingWaiters = [];
    this.decoder = new RawWebSocketFrameDecoder({
      onPing: (payload) => {
        this.writeFrame(0xA, payload);
        this.resolvePingWaiters();
      },
      onClose: () => this.close(),
    });
  }

  connect() {
    return new Promise((resolve, reject) => {
      const isSecure = this.url.protocol === 'wss:';
      const port = Number(this.url.port || (isSecure ? 443 : 80));
      const socketFactory = isSecure ? tls.connect : net.createConnection;
      const key = crypto.randomBytes(16).toString('base64');
      const path = `${this.url.pathname}${this.url.search}`;
      let handshakeBuffer = Buffer.alloc(0);
      let handshakeDone = false;

      const timer = setTimeout(() => {
        reject(new Error(`WebSocket handshake timeout: ${redactSensitiveUrl(this.url)}`));
        this.close();
      }, requestTimeoutMs);

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
          if (rest.length > 0) {
            this.decoder.read(rest);
          }
          return;
        }

        this.decoder.read(chunk);
      });

      this.socket.on('error', (error) => {
        clearTimeout(timer);
        reject(error);
        this.rejectPingWaiters(error);
      });

      this.socket.on('close', () => {
        this.rejectPingWaiters(new Error(`WebSocket closed: ${redactSensitiveUrl(this.url)}`));
      });
    });
  }

  waitForPing(timeoutMs) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(`Timed out waiting for WebSocket heartbeat ping: ${redactSensitiveUrl(this.url)}`));
      }, timeoutMs);
      this.pingWaiters.push({
        resolve: () => {
          clearTimeout(timer);
          resolve();
        },
        reject: (error) => {
          clearTimeout(timer);
          reject(error);
        },
      });
    });
  }

  resolvePingWaiters() {
    const waiters = this.pingWaiters.splice(0);
    waiters.forEach((waiter) => waiter.resolve());
  }

  rejectPingWaiters(error) {
    const waiters = this.pingWaiters.splice(0);
    waiters.forEach((waiter) => waiter.reject(error));
  }

  writeFrame(opcode, payload) {
    if (!this.socket || this.socket.destroyed) {
      return;
    }
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
  const user = await registerUser();
  const login = await loginUser(user);
  const ticket = await issueWebSocketTicket(login);
  const url = new URL(wsBaseUrl);
  url.searchParams.set('ticket', ticket.ticket);

  const ws = new RawWebSocket(url.toString());
  try {
    await ws.connect();
    await ws.waitForPing(heartbeatTimeoutMs);
  } finally {
    ws.close();
  }

  console.log(JSON.stringify({
    ok: true,
    checked: ['websocket-handshake', 'heartbeat-ping'],
    heartbeatTimeoutMs,
  }));
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
