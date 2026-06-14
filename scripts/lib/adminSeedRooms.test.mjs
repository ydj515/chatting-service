import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseRooms } from './adminSeedRooms.mjs';

test('parseRooms fails fast when rooms resolve to empty', () => {
  assert.throws(
    () => parseRooms(','),
    /--rooms must contain at least one room/,
  );
});

test('parseRooms parses supported heat levels', () => {
  const rooms = parseRooms('normal:1,hot:1,very-hot:1');

  assert.deepEqual(
    rooms.map((room) => room.heatLevel),
    ['NORMAL', 'HOT', 'VERY_HOT'],
  );
});
