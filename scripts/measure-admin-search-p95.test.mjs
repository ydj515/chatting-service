import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';

test('invalid legacy warm p95 target reports the legacy option name', () => {
  const result = spawnSync(
    process.execPath,
    [
      'scripts/measure-admin-search-p95.mjs',
      '--token',
      'test',
      '--target-p95-ms',
      'bad',
    ],
    {
      cwd: new URL('..', import.meta.url),
      encoding: 'utf8',
    },
  );

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /--target-p95-ms must be a positive integer/);
  assert.doesNotMatch(result.stderr, /--target-warm-p95-ms must be a positive integer/);
});

test('invalid explicit warm p95 target reports the explicit warm option name', () => {
  const result = spawnSync(
    process.execPath,
    [
      'scripts/measure-admin-search-p95.mjs',
      '--token',
      'test',
      '--target-warm-p95-ms',
      'bad',
    ],
    {
      cwd: new URL('..', import.meta.url),
      encoding: 'utf8',
    },
  );

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /--target-warm-p95-ms must be a positive integer/);
});

test('invalid slow query plan capture mode reports the option name', () => {
  const result = spawnSync(
    process.execPath,
    [
      'scripts/measure-admin-search-p95.mjs',
      '--token',
      'test',
      '--slow-query-plan',
      'always',
    ],
    {
      cwd: new URL('..', import.meta.url),
      encoding: 'utf8',
    },
  );

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /--slow-query-plan must be one of off, on-cold-failure/);
});
