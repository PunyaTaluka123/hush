#!/usr/bin/env bash
# End-to-end scenarios (SPEC §12): builds the firmware core for the simulator and
# the Cloud Functions, starts the Firebase emulators, runs sim/tests.
#   ./sim/run_e2e.sh                 all
#   ./sim/run_e2e.sh -k s5           pytest args pass through
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21 ]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@21; fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
make -s -C firmware/tests/host lib
npm --prefix backend/functions run -s build
args=$(printf '%q ' "$@")
cd backend
functions/node_modules/.bin/firebase emulators:exec --project demo-hush --only auth,firestore,functions \
  "cd ../sim && python3 -m pytest -q tests $args"
