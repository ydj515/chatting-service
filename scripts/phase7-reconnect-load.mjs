#!/usr/bin/env node
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import net from 'node:net';
import tls from 'node:tls';
import { URL } from 'node:url';
import {
  buildReconnectAttemptPlan,
  buildWebSocketSocketOptions,
  classifyTicketIssueFailure,
  exitCodeForReconnectSummary,
  parseReconnectLoadArgs,
  summarizeReconnectAttempts,
  validateWebSocketHandshake,
} from './lib/phase7ReconnectLoadPlan.mjs';
import {
  buildLoadUsername,
} from './lib/loadChatPlan.mjs';

const httpBaseUrl = process.env.CHAT_HTTP_URL ?? 'http://localhost/api';
const wsBaseUrl = process.env.CHAT_WS_URL ?? 'ws://localhost/api/ws/chat';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function requestJson(path, options = {}, timeoutMs) {
  const base = new URL(httpBaseUrl);
  const basePath = base.pathname.endsWith('/') ? base.pathname : `${base.pathname}/`;
  const normalizedPath = path.startsWith('/') ? path.slice(1) : path;
  const url = new URL(`${basePath}${normalizedPath}`, base.origin);
  const response = await fetch(url, {
    ...options,
    signal: AbortSignal.timeout(timeoutMs),
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers ?? {}),
    },
  });
  const text = await response.text();
  if (!response.ok) {
    const error = new Error(`${options.method ?? 'GET'} ${url} failed: ${response.status} ${text}`);
    error.status = response.status;
    error.body = text;
    throw error;
  }
  return text ? JSON.parse(text) : null;
}

async function registerUser(prefix, timeoutMs) {
  const suffix = `${Date.now().toString(36)}${Math.floor(Math.random() * 100000).toString(36)}`;
  const username = buildLoadUsername(prefix, suffix);
  const password = 'password';
  const user = await requestJson('/users/register', {
    method: 'POST',
    body: JSON.stringify({ username, password, displayName: username }),
  }, timeoutMs);
  return { ...user, password };
}

async function loginUser(user, timeoutMs) {
  return requestJson('/users/login', {
    method: 'POST',
    body: JSON.stringify({ username: user.username, password: user.password }),
  }, timeoutMs);
}

function authorizationHeaders(login) {
  return { Authorization: `${login.tokenType} ${login.sessionToken}` };
}

async function issueWebSocketTicket(login, timeoutMs) {
  return requestJson('/ws-tickets', {
    method: 'POST',
    headers: authorizationHeaders(login),
  }, timeoutMs);
}

async function connectTicket(ticket, timeoutMs) {
  const url = new URL(wsBaseUrl);
  url.searchParams.set('ticket', ticket);
  const ws = new RawWebSocket(url.toString(), timeoutMs);
  await ws.connect();
  return ws;
}

class RawWebSocket {
  constructor(url, timeoutMs) {
    this.url = new URL(url);
    this.timeoutMs = timeoutMs;
    this.socket = null;
  }

  connect() {
    return new Promise((resolve, reject) => {
      const isSecure = this.url.protocol === 'wss:';
      const key = crypto.randomBytes(16).toString('base64');
      const socketFactory = isSecure ? tls.connect : net.createConnection;
      const socketOptions = buildWebSocketSocketOptions(this.url);
      const path = `${this.url.pathname}${this.url.search}`;
      let handshakeBuffer = Buffer.alloc(0);
      let settled = false;
      const timer = setTimeout(() => {
        reject(new Error(`WebSocket handshake timeout: ${redactSensitiveUrl(this.url)}`));
        this.close();
      }, this.timeoutMs);

      this.socket = socketFactory(socketOptions, () => {
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
        if (settled) {
          return;
        }
        handshakeBuffer = Buffer.concat([handshakeBuffer, chunk]);
        const headerEnd = handshakeBuffer.indexOf('\r\n\r\n');
        if (headerEnd === -1) {
          return;
        }
        const header = handshakeBuffer.subarray(0, headerEnd).toString('utf8');
        clearTimeout(timer);
        settled = true;
        const validation = validateWebSocketHandshake(header, key);
        if (!validation.ok) {
          reject(new Error(`WebSocket handshake failed: ${validation.statusLine} (${validation.reason})`));
          this.close();
          return;
        }
        resolve();
      });

      this.socket.on('error', (error) => {
        clearTimeout(timer);
        if (!settled) {
          settled = true;
          reject(error);
        }
      });
    });
  }

  close() {
    if (this.socket && !this.socket.destroyed) {
      this.socket.destroy();
    }
  }
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

async function main() {
  const options = parseReconnectLoadArgs(process.argv.slice(2));
  const startedAt = Date.now();
  const clients = [];
  for (let index = 0; index < options.clients; index += 1) {
    const user = await registerUser(`reconn_${index}`, options.timeoutMs);
    const login = await loginUser(user, options.timeoutMs);
    clients.push({ user, login });
  }

  if (options.readyFile) {
    // chaos orchestrator가 storm 시작 시점에 맞춰 gateway 장애를 주입할 수 있도록,
    // 등록을 끝내고 attempt phase를 시작하기 직전에 ready 신호를 남긴다.
    await fs.writeFile(
      options.readyFile,
      JSON.stringify({ startedAt: Date.now(), clients: options.clients }),
    );
  }

  const attemptPlan = buildReconnectAttemptPlan(options);
  const attempts = await Promise.all(attemptPlan.map(async (attempt) => {
    await sleep(attempt.delayMs);
    const client = clients[attempt.clientIndex];
    const baseEvent = {
      scenario: options.scenario,
      clientIndex: attempt.clientIndex,
      attemptIndex: attempt.attemptIndex,
      cohort: options.cohort,
      reason: options.reason,
      ticketIssued: false,
      rateLimited: false,
      handshakeSucceeded: false,
      handshakeFailed: false,
    };
    let ticket;
    try {
      ticket = await issueWebSocketTicket(client.login, options.timeoutMs);
      baseEvent.ticketIssued = true;
    } catch (error) {
      const failureOutcome = classifyTicketIssueFailure(error);
      return {
        ...baseEvent,
        failureOutcome,
        rateLimited: failureOutcome.startsWith('rate_limited'),
      };
    }

    let ws = null;
    try {
      ws = await connectTicket(ticket.ticket, options.timeoutMs);
      return {
        ...baseEvent,
        handshakeSucceeded: true,
      };
    } catch (error) {
      return {
        ...baseEvent,
        handshakeFailed: true,
        failureOutcome: 'handshake_failure',
        error: error.message,
      };
    } finally {
      ws?.close();
    }
  }));

  const durationSeconds = Math.max(1, Math.ceil((Date.now() - startedAt) / 1000));
  const summary = summarizeReconnectAttempts(attempts, {
    scenario: options.scenario,
    durationSeconds,
    minTicketIssueSuccessRatio: options.minTicketIssueSuccessRatio,
    minHandshakeSuccessRatio: options.minHandshakeSuccessRatio,
    maxRateLimitFailureRatio: options.maxRateLimitFailureRatio,
    maxCohortFailureRatio: options.maxCohortFailureRatio,
  });
  console.log(JSON.stringify(summary, null, 2));
  process.exitCode = exitCodeForReconnectSummary(summary);
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
