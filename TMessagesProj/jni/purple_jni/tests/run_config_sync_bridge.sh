#!/usr/bin/env bash
set -euo pipefail
Tests="$(cd "$(dirname "$0")" && pwd)"
exec python3 "$Tests/run_modern_sync.py" --suite core "$@"
