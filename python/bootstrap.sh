#!/usr/bin/env bash
# Create the virtualenv and install pinned dependencies. Idempotent.
set -euo pipefail
cd "$(dirname "$0")"
[ -d .venv ] || python3 -m venv .venv
.venv/bin/pip install --quiet --upgrade pip
.venv/bin/pip install --quiet --require-hashes -r requirements.txt 2>/dev/null \
  || .venv/bin/pip install --quiet -r requirements.txt
