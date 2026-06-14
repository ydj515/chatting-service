import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildPsqlCommand } from './psqlCommand.mjs';

test('buildPsqlCommand uses local psql by default', () => {
  const command = buildPsqlCommand(
    { host: 'localhost', port: '5432', user: 'chatuser', password: 'chatpass', name: 'chatdb' },
    { mode: 'local' },
  );

  assert.equal(command.bin, 'psql');
  assert.deepEqual(command.args.slice(0, 8), [
    '-h',
    'localhost',
    '-p',
    '5432',
    '-U',
    'chatuser',
    '-d',
    'chatdb',
  ]);
});

test('buildPsqlCommand can run psql through docker compose exec', () => {
  const command = buildPsqlCommand(
    { host: 'localhost', port: '5432', user: 'chatuser', password: 'chatpass', name: 'chatdb' },
    { mode: 'docker-compose', service: 'postgres' },
  );

  assert.equal(command.bin, 'docker');
  assert.deepEqual(command.args.slice(0, 8), [
    'compose',
    'exec',
    '-T',
    '-e',
    'PGPASSWORD=chatpass',
    'postgres',
    'psql',
    '-h',
  ]);
});
