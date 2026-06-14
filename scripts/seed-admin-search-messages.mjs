#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { parseRooms } from './lib/adminSeedRooms.mjs';
import { buildPsqlCommand } from './lib/psqlCommand.mjs';
import { writeChunk } from './lib/streamWrite.mjs';

const args = parseArgs(process.argv.slice(2));
const messageCount = positiveInteger(args.messages, '--messages');
const rooms = parseRooms(args.rooms ?? 'normal:1');
const dryRun = args['dry-run'] === true;
const batchSize = positiveInteger(args['batch-size'] ?? '10000', '--batch-size');
const userCount = positiveInteger(args.users ?? '100', '--users');
const userIdStart = positiveInteger(args['user-id-start'] ?? '1000000', '--user-id-start');
const seedId = args['seed-id'] ?? Date.now().toString(36);
const psqlMode = psqlModeValue(args['psql-mode'] ?? 'local');
const psqlService = args['psql-service'] ?? 'postgres';
const db = {
  host: args.host ?? process.env.DB_HOST ?? 'localhost',
  port: args.port ?? process.env.DB_PORT ?? '5432',
  name: args.database ?? process.env.DB_NAME ?? 'chatdb',
  user: args.username ?? process.env.DB_USERNAME ?? 'chatuser',
  password: args.password ?? process.env.DB_PASSWORD ?? 'chatpass',
};

const plan = buildPlan({
  messageCount,
  rooms,
  batchSize,
  userCount,
  userIdStart,
  seedId,
  db,
  psqlMode,
  psqlService,
});
if (dryRun) {
  console.log(JSON.stringify(plan, null, 2));
  process.exit(0);
}

await runPsql(db, { psqlMode, psqlService }, [
  'CREATE EXTENSION IF NOT EXISTS pg_trgm;',
  "SELECT create_chat_messages_daily_partition(current_date, 16);",
  seedUsersSql(userCount, userIdStart),
  seedRoomStorageConfigsSql(rooms),
].join('\n'));

await copyMessages(db, { messageCount, rooms, userCount, userIdStart, seedId, batchSize, psqlMode, psqlService });
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

function psqlModeValue(value) {
  if (value === 'local' || value === 'docker-compose') {
    return value;
  }
  throw new Error('--psql-mode must be one of local, docker-compose.');
}

function buildPlan({
  messageCount,
  rooms,
  batchSize,
  userCount,
  userIdStart,
  seedId,
  db,
  psqlMode,
  psqlService,
}) {
  return {
    messageCount,
    roomCount: rooms.length,
    roomHeatCounts: rooms.reduce((acc, room) => {
      acc[room.heatLevel] = (acc[room.heatLevel] ?? 0) + 1;
      return acc;
    }, {}),
    batchSize,
    userCount,
    userIdStart,
    seedId,
    database: {
      host: db.host,
      port: db.port,
      name: db.name,
      user: db.user,
    },
    psql: {
      mode: psqlMode,
      service: psqlMode === 'docker-compose' ? psqlService : null,
    },
  };
}

function seedUsersSql(userCount, userIdStart) {
  const userIdEnd = userIdStart + userCount - 1;
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
FROM generate_series(${userIdStart}, ${userIdEnd}) gs
ON CONFLICT (id) DO UPDATE
SET username = EXCLUDED.username,
    display_name = EXCLUDED.display_name,
    is_active = true,
    updated_at = now();
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

function runPsql(db, options, sql) {
  return new Promise((resolve, reject) => {
    const command = buildPsqlCommand(db, {
      mode: options.psqlMode,
      service: options.psqlService,
    });
    const child = spawn(
      command.bin,
      command.args,
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

function copyMessages(db, {
  messageCount,
  rooms,
  userCount,
  userIdStart,
  seedId,
  batchSize,
  psqlMode,
  psqlService,
}) {
  return new Promise((resolve, reject) => {
    const command = buildPsqlCommand(db, {
      mode: psqlMode,
      service: psqlService,
    });
    const child = spawn(
      command.bin,
      [
        ...command.args,
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

    writeMessages(child.stdin, { messageCount, rooms, userCount, userIdStart, seedId, batchSize })
      .then(() => child.stdin.end('\\.\n'))
      .catch((error) => {
        child.kill('SIGTERM');
        reject(error);
      });
  });
}

async function writeMessages(stdin, { messageCount, rooms, userCount, userIdStart, seedId, batchSize }) {
  const roomPicker = weightedRoomPicker(rooms);
  const now = Date.now();
  for (let i = 1; i <= messageCount; i += 1) {
    const room = roomPicker(i);
    const senderId = userIdStart + ((i - 1) % userCount);
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
    await writeChunk(stdin, `${row}\n`);

    if (i % batchSize === 0) {
      console.error(`Seed progress: ${i}/${messageCount}`);
    }
  }
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
