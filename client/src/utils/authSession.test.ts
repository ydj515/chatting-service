import assert from 'node:assert/strict';
import { test } from 'vitest';
import { isApiSessionReady } from '@/utils/authSession.ts';
import type { User } from '@/types/index.ts';

const user: User = {
  id: 1,
  username: 'tester',
  displayName: 'Tester',
  isActive: true,
  createdAt: '2026-06-12T12:00:00',
};

test('인증 사용자는 API 세션 토큰 동기화 전까지 작업 영역을 열지 않는다', () => {
  assert.equal(isApiSessionReady(user, 'token-1', null), false);
  assert.equal(isApiSessionReady(user, 'token-1', 'token-2'), false);
});

test('인증 사용자는 API 세션 토큰이 같은 값으로 동기화된 뒤 작업 영역을 열 수 있다', () => {
  assert.equal(isApiSessionReady(user, 'token-1', 'token-1'), true);
});

test('미인증 상태는 API 세션 준비 게이트를 요구하지 않는다', () => {
  assert.equal(isApiSessionReady(null, null, null), true);
});
