import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const REPORT = new URL(
  '../../production-readiness-assessment-2026-06-28.html',
  import.meta.url,
);

test('production readiness report defines border color as a separate CSS variable', () => {
  const html = readFileSync(REPORT, 'utf8');

  assert.match(html, /--panel-3: #222a35;\n\s*--border: #303846;/);
  assert.doesNotMatch(html, /--panel-3: #222a35 --border:/);
});

test('production readiness verification table labels abbreviated command evidence', () => {
  const html = readFileSync(REPORT, 'utf8');

  assert.match(html, /<th>명령 또는 실행 요약<\/th>/);
  assert.match(html, /요약: node --test scripts\/lib\/phase8\*\.test\.mjs/);
  assert.match(html, /요약: \.\/gradlew :chat-persistence:test/);
  assert.match(html, /요약: docker compose exec chat-api-app-\* env/);
});
