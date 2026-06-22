#!/usr/bin/env node
import { randomBytes } from 'node:crypto';
import http from 'node:http';
import https from 'node:https';
import {
  buildRoleRoutingChecks,
  buildWebSocketHandshakeRequestOptions,
  evaluateHttpCheckResponse,
  evaluateWebSocketHandshakeResponse,
  exitCodeForSummary,
  parseRoleRoutingCheckArgs,
  summarizeRoleRoutingChecks,
} from './lib/phase7RoleRoutingCheckPlan.mjs';

async function main() {
  const options = parseRoleRoutingCheckArgs(process.argv.slice(2));
  const checks = buildRoleRoutingChecks(options);
  const results = [];

  for (const check of checks) {
    try {
      if (check.kind === 'websocket-handshake') {
        const response = await requestWebSocketHandshake(check);
        results.push(evaluateWebSocketHandshakeResponse(check, response));
      } else {
        const response = await requestHttp(check);
        results.push(evaluateHttpCheckResponse(check, response));
      }
    } catch (error) {
      results.push(requestFailure(check, error));
    }
  }

  const summary = summarizeRoleRoutingChecks({ baseUrl: options.baseUrl, checks: results });
  console.log(JSON.stringify(summary, null, 2));
  process.exitCode = exitCodeForSummary(summary);
}

async function requestHttp(check) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), check.timeoutMs);
  try {
    const fetchOptions = {
      method: check.method,
      headers: check.headers,
      signal: controller.signal,
    };
    // GET 요청에 body(undefined/'')를 넘기면 일부 fetch 구현에서 TypeError가 나므로 정의된 경우에만 추가한다.
    if (check.body !== undefined) {
      fetchOptions.body = check.body;
    }
    const response = await fetch(check.url, fetchOptions);
    return {
      status: response.status,
      headers: Object.fromEntries(response.headers.entries()),
      body: await response.text(),
    };
  } finally {
    clearTimeout(timeout);
  }
}

function requestWebSocketHandshake(check) {
  const url = new URL(check.url);
  const transport = url.protocol === 'https:' ? https : http;
  const requestOptions = buildWebSocketHandshakeRequestOptions(
    check,
    randomBytes(16).toString('base64'),
  );

  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (fn, value) => {
      if (settled) {
        return;
      }
      settled = true;
      fn(value);
    };

    const request = transport.request(requestOptions, (response) => {
      // 응답 본문을 읽는 중 연결이 끊기면 response 스트림이 error를 emit한다. 처리하지 않으면 프로세스가 죽는다.
      response.on('error', (error) => finish(reject, error));
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => {
        finish(resolve, {
          status: response.statusCode,
          headers: response.headers,
          body: Buffer.concat(chunks).toString('utf8'),
        });
      });
    });

    request.on('upgrade', (response, socket) => {
      socket.destroy();
      finish(resolve, {
        status: response.statusCode,
        headers: response.headers,
        body: '',
      });
    });
    request.on('timeout', () => {
      request.destroy(new Error(`Timed out after ${check.timeoutMs}ms`));
    });
    request.on('error', (error) => finish(reject, error));
    request.end();
  });
}

function requestFailure(check, error) {
  return {
    name: check.name,
    method: check.method,
    url: check.url,
    ok: false,
    status: null,
    reason: 'request_failed',
    error: error.message,
  };
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
