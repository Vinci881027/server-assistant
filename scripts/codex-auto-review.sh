#!/usr/bin/env bash
set -euo pipefail

if ! command -v codex >/dev/null 2>&1; then
  echo "codex command not found in PATH" >&2
  exit 127
fi

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 \"<task prompt>\"" >&2
  echo "Example: $0 \"修復 CommandType enum 的空值處理\"" >&2
  exit 2
fi

PROMPT="$*"

echo "[1/2] Running Codex task..."
codex exec "$PROMPT"

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "[2/2] Auto-reviewing latest changes..."
  codex review --uncommitted "review 剛剛的修改"
else
  echo "[2/2] Skip auto-review: current directory is not a git repository."
fi
