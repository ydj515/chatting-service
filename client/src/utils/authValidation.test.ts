import test from 'node:test';
import assert from 'node:assert/strict';
import {
  getAuthErrorMessage,
  getAuthFieldPlaceholder,
  validateAuthForm,
} from './authValidation.ts';

test('회원가입 필드 placeholder가 서버 validation 제약을 보여준다', () => {
  assert.equal(getAuthFieldPlaceholder('username'), '사용자명 (3-20자)');
  assert.equal(getAuthFieldPlaceholder('displayName'), '표시명 (1-50자)');
  assert.equal(getAuthFieldPlaceholder('password'), '비밀번호 (최소 3자)');
});

test('회원가입 사용자명은 3-20자만 허용한다', () => {
  assert.deepEqual(
    validateAuthForm(
      { username: 'ab', password: 'abc', displayName: '테스터' },
      'register',
    ),
    { isValid: false, message: '사용자명은 3-20자 사이여야 합니다' },
  );
});

test('회원가입 비밀번호는 최소 3자 이상이어야 한다', () => {
  assert.deepEqual(
    validateAuthForm(
      { username: 'tester', password: '12', displayName: '테스터' },
      'register',
    ),
    { isValid: false, message: '비밀번호는 최소 3자 이상이어야 합니다' },
  );
});

test('회원가입 표시명은 1-50자만 허용한다', () => {
  assert.deepEqual(
    validateAuthForm(
      { username: 'tester', password: 'abc', displayName: '가'.repeat(51) },
      'register',
    ),
    { isValid: false, message: '표시 이름은 1-50자 사이여야 합니다' },
  );
});

test('서버가 메시지 없는 400을 반환하면 입력 규칙 안내 문구를 보여준다', () => {
  assert.equal(
    getAuthErrorMessage({
      response: {
        status: 400,
        data: {
          status: 400,
          error: 'Bad Request',
        },
      },
      message: 'Request failed with status code 400',
    }),
    '입력값을 확인해주세요. 사용자명은 3-20자, 비밀번호는 최소 3자, 표시명은 1-50자입니다.',
  );
});

test('서버 validation errors 배열이 있으면 구체 메시지를 우선 보여준다', () => {
  assert.equal(
    getAuthErrorMessage({
      response: {
        status: 400,
        data: {
          status: 400,
          error: 'BAD_REQUEST',
          message: '입력값 검증에 실패했습니다.',
          errors: [
            { field: 'username', message: '사용자명은 3-20자 사이여야 합니다' },
            { field: 'password', message: '비밀번호는 최소 3자 이상이어야 합니다' },
          ],
        },
      },
    }),
    '사용자명은 3-20자 사이여야 합니다\n비밀번호는 최소 3자 이상이어야 합니다',
  );
});
