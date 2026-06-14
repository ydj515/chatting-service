#!/usr/bin/env node
import { spawn } from 'node:child_process';

const args = parseArgs(process.argv.slice(2));
const messageCount = positiveInteger(args.messages, '--messages');
const rooms = parseRooms(args.rooms ?? 'normal:1');
const dryRun = args['dry-run'] === true;
const batchSize = positiveInteger(args['batch-size'] ?? '10000', '--batch-size');
const userCount = positiveInteger(args.users ?? '100', '--users');
const seedId = args['seed-id'] ?? Date.now().toString(36);
const db = {
  host: args.host ?? process.env.DB_HOST ?? 'localhost',
  port: args.port ?? process.env.DB_PORT ?? '5432',
  name: args.database ?? process.env.DB_NAME ?? 'chatdb',
  user: args.username ?? process.env.DB_USERNAME ?? 'chatuser',
  password: args.password ?? process.env.DB_PASSWORD ?? 'chatpass',
};

const plan = buildPlan({ messageCount, rooms, batchSize, userCount, seedId, db });
if (dryRun) {
  console.log(JSON.stringify(plan, null, 2));
  process.exit(0);
}

await runPsql(db, [
  'CREATE EXTENSION IF NOT EXISTS pg_trgm;',
  "SELECT create_chat_messages_daily_partition(current_date, 16);",
  seedUsersSql(userCount),
  seedRoomStorageConfigsSql(rooms),
].join('\n'));

await copyMessages(db, { messageCount, rooms, userCount, seedId });
console.log(`Seeded ${messageCount} admin search messages across ${rooms.length} rooms.`);

function parseArgs(argv) {
  const parsed = {};
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith('--')) {
      throw new Error(`Unexpected argument: ${token}`);
    }
    const key = token.slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith('--')) {
      parsed[key] = true;
    } else {
      parsed[key] = next;
      i += 1;
    }
  }
  return parsed;
}

function positiveInteger(value, name) {
  const parsed = Number.parseInt(String(value), 10);
  if (!Number.isSafeInteger(parsed) || parsed < 1 || String(value).match(/[^0-9]/)) {
    throw new Error(`${name} must be a positive integer.`);
  }
  return parsed;
}

function parseRooms(value) {
  return String(value)
    .split(',')
    .filter(Boolean)
    .flatMap((entry) => {
      const [rawHeat, rawCount] = entry.split(':');
      const heat = heatLevel(rawHeat);
      const count = positiveInteger(rawCount, `room count for ${rawHeat}`);
      return Array.from({ length: count }, (_, index) => ({
        id: roomIdFor(heat, index),
        heatLevel: heat,
        weight: heatWeight(heat),
      }));
    });
}

function heatLevel(value) {
  const normalized = String(value ?? '').trim().toLowerCase();
  if (normalized === 'normal') return 'NORMAL';
  if (normalized === 'hot') return 'HOT';
  if (normalized === 'very-hot' || normalized === 'very_hot') return 'VERY_HOT';
  throw new Error(`Unsupported room heat level: ${value}`);
}

function heatWeight(heat) {
  if (heat === 'VERY_HOT') return 20;
  if (heat === 'HOT') return 5;
  return 1;
}

function roomIdFor(heat, index) {
  const base = heat === 'VERY_HOT' ? 30_000 : heat === 'HOT' ? 20_000 : 10_000;
  return base + index + 1;
}

function buildPlan({ messageCount, rooms, batchSize, userCount, seedId, db }) {
  return {
    messageCount,
    roomCount: rooms.length,
    roomHeatCounts: rooms.reduce((acc, room) => {
      acc[room.heatLevel] = (acc[room.heatLevel] ?? 0) + 1;
      return acc;
    }, {}),
    batchSize,
    userCount,
    seedId,
    database: {
      host: db.host,
      port: db.port,
      name: db.name,
      user: db.user,
    },
  };
}

function seedUsersSql(userCount) {
  return `
INSERT INTO app_users (id, username, password, display_name, is_active, created_at, updated_at)
SELECT
    gs,
    'seed_user_' || gs,
    'not-used',
    'Seed User ' || gs,
    true,
    now(),
    now()
FROM generate_series(1, ${userCount}) gs
ON CONFLICT (username) DO NOTHING;
`;
}

function seedRoomStorageConfigsSql(rooms) {
  const values = rooms
    .map((room) => `(${room.id}, '${room.heatLevel}', now())`)
    .join(',');
  return `
INSERT INTO room_storage_configs (room_id, hot_room_policy, updated_at)
VALUES ${values}
ON CONFLICT (room_id) DO UPDATE
SET hot_room_policy = EXCLUDED.hot_room_policy,
    updated_at = EXCLUDED.updated_at;
`;
}

function runPsql(db, sql) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      'psql',
      [
        '-h', db.host,
        '-p', String(db.port),
        '-U', db.user,
        '-d', db.name,
        '-v', 'ON_ERROR_STOP=1',
      ],
      {
        env: { ...process.env, PGPASSWORD: db.password },
        stdio: ['pipe', 'inherit', 'inherit'],
      },
    );
    child.stdin.end(sql);
    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`psql exited with code ${code}`));
    });
  });
}

function copyMessages(db, { messageCount, rooms, userCount, seedId }) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      'psql',
      [
        '-h', db.host,
        '-p', String(db.port),
        '-U', db.user,
        '-d', db.name,
        '-v', 'ON_ERROR_STOP=1',
        '-c',
        `COPY chat_messages (
            message_id,
            client_message_id,
            room_id,
            room_seq,
            write_shard,
            sender_id,
            message_type,
            content,
            created_at
        ) FROM STDIN WITH (FORMAT csv)`,
      ],
      {
        env: { ...process.env, PGPASSWORD: db.password },
        stdio: ['pipe', 'inherit', 'inherit'],
      },
    );

    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`psql copy exited with code ${code}`));
    });

    const roomPicker = weightedRoomPicker(rooms);
    const now = Date.now();
    for (let i = 1; i <= messageCount; i += 1) {
      const room = roomPicker(i);
      const senderId = ((i - 1) % userCount) + 1;
      const createdAt = new Date(now - (messageCount - i) * 10).toISOString();
      const content = contentFor(room.heatLevel, i);
      const row = [
        `seed_${seedId}_${i}`,
        `seed_client_${seedId}_${i}`,
        room.id,
        i,
        i % 16,
        senderId,
        'TEXT',
        content,
        createdAt,
      ].map(csv).join(',');
      child.stdin.write(`${row}\n`);
    }
    child.stdin.end('\\.\n');
  });
}

function weightedRoomPicker(rooms) {
  const expanded = rooms.flatMap((room) => Array.from({ length: room.weight }, () => room));
  return (index) => expanded[(index - 1) % expanded.length];
}

function contentFor(heat, index) {
  const keyword = index % 10 === 0 ? 'hello searchable admin keyword' : 'live chat message';
  return `${keyword} ${heat.toLowerCase()} seq ${index}`;
}

function csv(value) {
  const text = String(value ?? '');
  if (/[",\n\r]/.test(text)) {
    return `"${text.replaceAll('"', '""')}"`;
  }
  return text;
}
