#!/bin/sh
# Creates or updates the four streams, the seven durable consumers and the
# JOB_CONTROL bucket from the JSON files next to this script (PI 5.1, 5.3).
# Safe to run repeatedly. The definitions are the single source; the worker and
# backend code create only their own consumers with the same settings, and a
# test compares them with these files.
set -eu
cd /provision
export NATS_URL="${NATS_URL:-nats://nats:4222}"

for f in streams/*.json; do
  name=$(jq -r .name "$f")
  if nats stream info "$name" >/dev/null 2>&1; then
    nats stream edit "$name" --config "$f" --force >/dev/null
    echo "stream $name: updated"
  else
    nats stream add --config "$f" >/dev/null
    echo "stream $name: created"
  fi
done

for f in consumers/*.json; do
  base=$(basename "$f" .json)
  stream=${base%%.*}
  durable=$(jq -r .durable_name "$f")
  if nats consumer info "$stream" "$durable" >/dev/null 2>&1; then
    nats consumer edit "$stream" "$durable" --config "$f" --force >/dev/null
    echo "consumer $stream/$durable: updated"
  else
    nats consumer add "$stream" --config "$f" >/dev/null
    echo "consumer $stream/$durable: created"
  fi
done

bucket=$(jq -r .bucket kv/JOB_CONTROL.json)
ttl=$(jq -r .ttl_seconds kv/JOB_CONTROL.json)
if nats kv info "$bucket" >/dev/null 2>&1; then
  echo "kv $bucket: exists"
else
  nats kv add "$bucket" --ttl "${ttl}s" --replicas "$(jq -r .replicas kv/JOB_CONTROL.json)" \
    --history "$(jq -r .history kv/JOB_CONTROL.json)" >/dev/null
  echo "kv $bucket: created"
fi
