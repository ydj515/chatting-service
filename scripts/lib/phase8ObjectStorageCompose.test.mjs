import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const compose = readFileSync(new URL('../../docker-compose.yml', import.meta.url), 'utf8');
const dockerConfig = readFileSync(
  new URL('../../chat-runtime-config/src/main/resources/application-docker.yml', import.meta.url),
  'utf8',
);
const envExample = readFileSync(new URL('../../.env.example', import.meta.url), 'utf8');
const mise = readFileSync(new URL('../../mise.toml', import.meta.url), 'utf8');
const archiveScript = readFileSync(
  new URL('../../infra/postgres/archive/archive-partitions.sh', import.meta.url),
  'utf8',
);
const archiveDockerfile = readFileSync(
  new URL('../../infra/postgres/archive/Dockerfile', import.meta.url),
  'utf8',
);

function serviceBlock(name) {
  const match = compose.match(
    new RegExp(`\\n  ${name}:\\n([\\s\\S]*?)(?=\\n  [a-zA-Z0-9_-]+:\\n|\\n# 볼륨 정의|\\nvolumes:)`),
  );
  assert.ok(match, `service ${name} should exist`);
  return match[1];
}

test('Compose includes loopback-bound MinIO and bucket init gate', () => {
  const minio = serviceBlock('minio');
  assert.match(minio, /minio\/minio:/);
  assert.match(minio, /server \/data --console-address ":9001"/);
  assert.match(minio, /127\.0\.0\.1:\$\{MINIO_API_PORT:-9000\}:9000/);
  assert.match(minio, /127\.0\.0\.1:\$\{MINIO_CONSOLE_PORT:-9001\}:9001/);
  assert.match(minio, /minio_data:\/data/);

  const init = serviceBlock('minio-init');
  assert.match(init, /minio\/mc:/);
  assert.match(init, /mc alias set chat-minio/);
  assert.match(init, /mc mb --ignore-existing/);
  assert.match(compose, /minio_data:/);
});

test('Docker profile exposes object storage properties to Spring apps', () => {
  assert.match(compose, /CHAT_OBJECT_STORAGE_ENABLED:/);
  assert.match(compose, /CHAT_OBJECT_STORAGE_ENDPOINT: \$\{CHAT_OBJECT_STORAGE_ENDPOINT:-http:\/\/minio:9000\}/);
  assert.match(compose, /CHAT_OBJECT_STORAGE_BUCKET:/);
  assert.match(compose, /CHAT_OBJECT_STORAGE_PRESIGNED_URL_TTL:/);
  assert.match(dockerConfig, /object-storage:/);
  assert.match(dockerConfig, /enabled: \$\{CHAT_OBJECT_STORAGE_ENABLED:true\}/);
  assert.match(dockerConfig, /endpoint: \$\{CHAT_OBJECT_STORAGE_ENDPOINT:http:\/\/localhost:9000\}/);
});

test('worker and admin app wait for the MinIO init gate', () => {
  for (const service of ['chat-worker-app-1', 'chat-admin-app-1']) {
    const block = serviceBlock(service);
    assert.match(block, /minio-init: \{ condition: service_completed_successfully \}/);
  }
});

test('local infra starts MinIO and env example documents object storage variables', () => {
  assert.match(mise, /minio minio-init/);
  assert.match(envExample, /CHAT_OBJECT_STORAGE_ENABLED=true/);
  assert.match(envExample, /CHAT_OBJECT_STORAGE_ENDPOINT=http:\/\/minio:9000/);
  assert.match(envExample, /CHAT_OBJECT_STORAGE_BUCKET=chat-archives/);
  assert.match(envExample, /MINIO_ROOT_USER=/);
  assert.match(envExample, /MINIO_ROOT_PASSWORD=/);
});

test('partition archive service uses an image with AWS CLI and waits for MinIO init', () => {
  const block = serviceBlock('postgres-partition-archive');
  assert.match(block, /build:\n\s+context: \./);
  assert.match(block, /dockerfile: infra\/postgres\/archive\/Dockerfile/);
  assert.match(block, /CHAT_PARTITION_ARCHIVE_OBJECT_STORAGE_ENABLED:/);
  assert.match(block, /CHAT_PARTITION_ARCHIVE_OBJECT_PREFIX:/);
  assert.match(block, /AWS_ACCESS_KEY_ID:/);
  assert.match(block, /AWS_SECRET_ACCESS_KEY:/);
  assert.match(block, /minio-init: \{ condition: service_completed_successfully \}/);
  assert.match(archiveDockerfile, /FROM postgres:17\.9-alpine/);
  assert.match(archiveDockerfile, /apk add --no-cache aws-cli/);
});

test('archive script uploads csv and metadata before allowing drop', () => {
  assert.match(archiveScript, /CHAT_PARTITION_ARCHIVE_OBJECT_STORAGE_ENABLED/);
  assert.match(archiveScript, /CHAT_PARTITION_ARCHIVE_OBJECT_PREFIX/);
  assert.match(archiveScript, /aws --endpoint-url/);
  assert.match(archiveScript, /s3 cp "\$archive_file"/);
  assert.match(archiveScript, /s3 cp "\$metadata_file"/);
  assert.match(archiveScript, /Object Storage upload is required before detach\/drop/);
  assert.match(archiveScript, /objectUri/);
  assert.match(archiveScript, /metadataObjectUri/);
  assert.match(archiveScript, /uploadedAt/);
});
