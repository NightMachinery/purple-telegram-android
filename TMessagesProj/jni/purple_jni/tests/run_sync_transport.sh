#!/usr/bin/env bash
set -euo pipefail
Root="$(cd "$(dirname "$0")/../../../.." && pwd)"
exec python3 "$Root/TMessagesProj/jni/purple_jni/tests/run_modern_sync.py" --suite auto "$@"
