#!/bin/sh
set -eu

nodes="redis-cluster-node-1:6379 redis-cluster-node-2:6379 redis-cluster-node-3:6379 redis-cluster-node-4:6379 redis-cluster-node-5:6379 redis-cluster-node-6:6379"

for endpoint in $nodes; do
  host="${endpoint%:*}"
  port="${endpoint#*:}"
  until redis-cli -h "$host" -p "$port" ping 2>/dev/null | grep -q PONG; do
    echo "Waiting for $endpoint..."
    sleep 1
  done
done

if redis-cli -h redis-cluster-node-1 -p 6379 cluster info 2>/dev/null | grep -q 'cluster_state:ok'; then
  echo "Redis Cluster is already healthy."
  exit 0
fi

redis-cli --cluster create $nodes --cluster-replicas 1 --cluster-yes

until redis-cli -h redis-cluster-node-1 -p 6379 cluster info 2>/dev/null | grep -q 'cluster_state:ok'; do
  echo "Waiting for Redis Cluster state ok..."
  sleep 1
done

echo "Redis Cluster is ready."
