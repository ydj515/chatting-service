import assert from 'node:assert/strict';
import { test } from 'vitest';
import { deriveServerHealthState } from '@/utils/serverHealth.ts';

test('서버 상태는 최초 로딩 중에는 checking이다', () => {
  const state = deriveServerHealthState({
    isPending: true,
    isError: false,
    isRefetchError: false,
    failureCount: 0,
    errorUpdatedAt: 0,
  });

  assert.equal(state.serverStatus, 'checking');
  assert.equal(state.isError, false);
});

test('서버 상태는 백그라운드 재조회 실패도 offline으로 처리한다', () => {
  const state = deriveServerHealthState({
    isPending: false,
    isError: false,
    isRefetchError: true,
    failureCount: 1,
    errorUpdatedAt: 1234,
  });

  assert.equal(state.serverStatus, 'offline');
  assert.equal(state.isError, true);
  assert.equal(state.errorUpdatedAt, 1234);
});

test('서버 상태는 실패 횟수만 증가한 재조회 실패도 offline으로 처리한다', () => {
  const state = deriveServerHealthState({
    isPending: false,
    isError: false,
    isRefetchError: false,
    failureCount: 2,
    errorUpdatedAt: 5678,
  });

  assert.equal(state.serverStatus, 'offline');
  assert.equal(state.isError, true);
});
