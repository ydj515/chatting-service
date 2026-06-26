#!/usr/bin/env node

const adminBaseUrl = process.env.CHAT_ADMIN_BASE_URL ?? 'http://localhost/api/admin';
const adminToken = process.env.CHAT_ADMIN_TOKEN ?? 'my-secure-admin-token-change-me';

async function main() {
  const blockedPattern = `phase85-blocked-${Date.now()}`;
  const unauthorized = await fetch(`${adminBaseUrl}/moderation/rules`);
  if (unauthorized.status !== 401) {
    throw new Error(`expected unauthenticated moderation rules request to return 401, got ${unauthorized.status}`);
  }

  const rule = await requestJson(`${adminBaseUrl}/moderation/rules`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'X-Admin-Token': adminToken,
    },
    body: JSON.stringify({
      scopeType: 'GLOBAL',
      pattern: blockedPattern,
      matchType: 'CONTAINS',
      action: 'REJECT',
      reason: 'phase8.5 smoke',
    }),
  });

  console.log(JSON.stringify({
    ok: true,
    createdRuleId: rule.id,
    checked: ['admin-auth-required', 'global-rule-created'],
    adminBaseUrl,
  }, null, 2));
}

async function requestJson(url, init) {
  const response = await fetch(url, init);
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`${init.method ?? 'GET'} ${url} failed with ${response.status}: ${body}`);
  }
  return body ? JSON.parse(body) : null;
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
