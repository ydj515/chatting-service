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
    const response = await fetch(check.url, {
      method: check.method,
      headers: check.headers,
      body: check.body,
      signal: controller.signal,
    });
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
