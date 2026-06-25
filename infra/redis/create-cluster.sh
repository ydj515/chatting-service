#!/bin/sh
set -eu

default_nodes="redis-cluster-node-1:6379,redis-cluster-node-2:6379,redis-cluster-node-3:6379,redis-cluster-node-4:6379,redis-cluster-node-5:6379,redis-cluster-node-6:6379"
raw_nodes="${REDIS_CLUSTER_NODES:-$default_nodes}"
nodes=$(printf '%s' "$raw_nodes" | tr ',' ' ')
bootstrap_timeout_seconds="${REDIS_CLUSTER_BOOTSTRAP_TIMEOUT_SECONDS:-90}"

set -- $nodes
if [ "$#" -eq 0 ]; then
  echo "REDIS_CLUSTER_NODES did not contain any Redis Cluster endpoints." >&2
  exit 1
fi
first_endpoint="$1"
first_host="${first_endpoint%:*}"
first_port="${first_endpoint#*:}"

deadline() {
  expr "$(date +%s)" + "$bootstrap_timeout_seconds"
}

fail_after_timeout() {
  echo "$1" >&2
  redis-cli -h "$first_host" -p "$first_port" cluster info 2>/dev/null || true
  redis-cli -h "$first_host" -p "$first_port" cluster nodes 2>/dev/null || true
  exit 1
}

ping_deadline="$(deadline)"
for endpoint in $nodes; do
  host="${endpoint%:*}"
  port="${endpoint#*:}"
  until redis-cli -h "$host" -p "$port" ping 2>/dev/null | grep -q PONG; do
    if [ "$(date +%s)" -ge "$ping_deadline" ]; then
      fail_after_timeout "Timed out waiting for Redis Cluster node $endpoint to answer PING."
    fi
    echo "Waiting for $endpoint..."
    sleep 1
  done
done

if redis-cli -h "$first_host" -p "$first_port" cluster info 2>/dev/null | grep -q 'cluster_state:ok'; then
  echo "Redis Cluster is already healthy."
  exit 0
fi

redis-cli --cluster create $nodes --cluster-replicas 1 --cluster-yes

cluster_deadline="$(deadline)"
until redis-cli -h "$first_host" -p "$first_port" cluster info 2>/dev/null | grep -q 'cluster_state:ok'; do
  if [ "$(date +%s)" -ge "$cluster_deadline" ]; then
    fail_after_timeout "Timed out waiting for Redis Cluster state ok."
  fi
  echo "Waiting for Redis Cluster state ok..."
  sleep 1
done

echo "Redis Cluster is ready."
