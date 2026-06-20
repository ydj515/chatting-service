import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const ddl = readFileSync(new URL('../../infra/postgres/message-partitions.sql', import.meta.url), 'utf8');

test('message partition DDL enables room-aware FTS indexing', () => {
  assert.match(ddl, /CREATE EXTENSION IF NOT EXISTS btree_gin;/);
  assert.match(ddl, /ix_chat_messages_default_room_content_tsv/);
  assert.match(ddl, /USING gin \(room_id int8_ops, content_tsv\)/);
  assert.match(ddl, /ix_%s_room_content_tsv/);
});

test('message partition DDL includes admin search cursor ordering indexes', () => {
  assert.match(ddl, /ix_chat_messages_default_admin_search_cursor/);
  assert.match(ddl, /created_at DESC, room_seq DESC, message_id DESC/);
  assert.match(ddl, /ix_%s_admin_search_cursor/);
});

test('message partition DDL includes admin room history cursor ordering indexes', () => {
  assert.match(ddl, /ix_chat_messages_default_admin_room_history_cursor/);
  assert.match(ddl, /room_id, room_seq DESC, created_at DESC, message_id DESC/);
  assert.match(ddl, /ix_%s_admin_room_history_cursor/);
});

test('admin export jobs include resume checkpoint columns', () => {
  assert.match(ddl, /cursor_token text/);
  assert.match(ddl, /exported_rows integer NOT NULL DEFAULT 0/);
  assert.match(ddl, /ADD COLUMN IF NOT EXISTS cursor_token text/);
  assert.match(ddl, /ADD COLUMN IF NOT EXISTS exported_rows integer NOT NULL DEFAULT 0/);
});

test('room storage configs include Phase 6 room policy guard columns', () => {
  assert.match(ddl, /auto_policy_enabled boolean NOT NULL DEFAULT true/);
  assert.match(ddl, /moderator_priority boolean NOT NULL DEFAULT true/);
  assert.match(ddl, /ALTER COLUMN auto_policy_enabled SET DEFAULT true/);
  assert.match(ddl, /ALTER COLUMN moderator_priority SET DEFAULT true/);
});
