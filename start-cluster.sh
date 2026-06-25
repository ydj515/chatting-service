#!/usr/bin/env bash
set -euo pipefail

compose=(docker compose)
db_name="${DB_NAME:-chatdb}"
db_username="${DB_USERNAME:-chatuser}"

echo "Starting distributed chat system..."

if ! command -v docker >/dev/null 2>&1; then
    echo "Docker가 설치되어 있지 않습니다."
    exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
    echo "Docker Compose v2를 사용할 수 없습니다."
    exit 1
fi

read -r -p "기존 컨테이너와 볼륨을 정리하시겠습니까? (y/N): " cleanup
if [[ "$cleanup" =~ ^[Yy]$ ]]; then
    echo "기존 컨테이너와 볼륨을 정리합니다..."
    "${compose[@]}" down --volumes --remove-orphans
fi

echo "PostgreSQL primary/read-replica, archive worker, Redis Cluster를 시작합니다..."
"${compose[@]}" up -d \
    postgres \
    postgres-primary-setup \
    postgres-replica \
    postgres-partition-archive \
    redis-cluster-node-1 \
    redis-cluster-node-2 \
    redis-cluster-node-3 \
    redis-cluster-node-4 \
    redis-cluster-node-5 \
    redis-cluster-node-6 \
    redis-cluster-init

echo "PostgreSQL 준비를 기다립니다..."
until "${compose[@]}" exec -T postgres pg_isready -U "$db_username" -d "$db_name" >/dev/null 2>&1; do
    sleep 2
done

echo "Redis Cluster 준비를 기다립니다..."
until "${compose[@]}" exec -T redis-cluster-node-1 redis-cli -p 6379 cluster info 2>/dev/null | grep -q 'cluster_state:ok'; do
    sleep 2
done

echo "역할별 애플리케이션과 nginx를 빌드 및 시작한 뒤 health를 기다립니다..."
"${compose[@]}" up -d --build --wait --wait-timeout 180 \
    chat-api-app-1 \
    chat-api-app-2 \
    chat-websocket-app-1 \
    chat-websocket-app-2 \
    chat-worker-app-1 \
    chat-admin-app-1 \
    nginx

echo "nginx upstream DNS를 갱신합니다..."
"${compose[@]}" restart nginx
"${compose[@]}" up -d --wait --wait-timeout 60 nginx

echo "서비스 상태를 확인합니다..."
"${compose[@]}" ps

cat <<'EOF'

분산 채팅 시스템이 시작되었습니다.

모니터링:
  docker compose logs -f chat-api-app-1 chat-api-app-2
  docker compose logs -f chat-websocket-app-1 chat-websocket-app-2
  docker compose logs -f chat-worker-app-1 chat-admin-app-1
  docker compose logs -f nginx
  docker stats

Redis Cluster 모니터링:
  docker exec -it chat-redis-cluster-node-1 redis-cli -p 6379 cluster nodes
  docker exec -it chat-redis-cluster-node-1 redis-cli -p 6379 cluster info
  docker exec -it chat-redis-cluster-node-1 redis-cli -p 6379 MONITOR

채팅 검증:
  mise run verify:chat

종료:
  docker compose down
EOF
