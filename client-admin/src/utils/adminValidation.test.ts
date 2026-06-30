import assert from 'node:assert/strict';
import { test } from 'vitest';
import { isValidRoomId, numberOrNull } from '@/utils/adminValidation.ts';

test('numberOrNull은 빈 값과 숫자가 아닌 값을 null로 변환한다', () => {
  assert.equal(numberOrNull(''), null);
  assert.equal(numberOrNull('   '), null);
  assert.equal(numberOrNull('abc'), null);
});

test('numberOrNull은 숫자 문자열을 number로 변환한다', () => {
  assert.equal(numberOrNull('10'), 10);
  assert.equal(numberOrNull(' 20 '), 20);
});

test('isValidRoomId는 빈 값 기본값과 숫자 roomId만 허용한다', () => {
  assert.equal(isValidRoomId(''), true);
  assert.equal(isValidRoomId('1'), true);
  assert.equal(isValidRoomId('room-1'), false);
});
