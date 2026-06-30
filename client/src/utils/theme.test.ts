import assert from 'node:assert/strict';
import { test } from 'vitest';
import {
  getNextTheme,
  resolveInitialTheme,
  syncDocumentTheme,
} from '@/theme.ts';

test('저장된 light/dark 테마를 우선 사용한다', () => {
  assert.equal(resolveInitialTheme('light', true), 'light');
  assert.equal(resolveInitialTheme('dark', false), 'dark');
});

test('저장된 테마가 없거나 잘못되면 OS 선호도를 따른다', () => {
  assert.equal(resolveInitialTheme(null, true), 'dark');
  assert.equal(resolveInitialTheme('', false), 'light');
  assert.equal(resolveInitialTheme('blue', true), 'dark');
});

test('테마 토글은 light와 dark 사이를 오간다', () => {
  assert.equal(getNextTheme('light'), 'dark');
  assert.equal(getNextTheme('dark'), 'light');
});

test('문서 테마 동기화는 data-theme를 설정하고 기존 dark 클래스를 제거한다', () => {
  const removedClasses: string[] = [];
  const attributes = new Map<string, string>();

  syncDocumentTheme(
    {
      setAttribute(name: string, value: string) {
        attributes.set(name, value);
      },
      classList: {
        remove(name: string) {
          removedClasses.push(name);
        },
      },
    },
    'dark',
  );

  assert.equal(attributes.get('data-theme'), 'dark');
  assert.deepEqual(removedClasses, ['dark']);
});
