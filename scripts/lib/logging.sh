#!/bin/bash
# DiLink-Auto Issue Agent — Logging helpers
# Sourced by scripts/issue-agent.sh and scripts/lib/github-api.sh

log_step() { echo "▶ $*"; }
log_ok()   { echo "  ✓ $*"; }
log_err()  { echo "  ✗ $*"; }

trap 'if [ "${-//[^e]/}" = "e" ]; then log_err "CRASH at line $LINENO (exit=$?)"; fi' ERR
