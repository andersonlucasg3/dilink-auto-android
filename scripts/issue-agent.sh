#!/bin/bash
set -euo pipefail

# ============================================================
# DiLink-Auto Issue Agent
# Orchestrates Claude Code to work on GitHub issues autonomously.
# Triggered by .github/workflows/issue-agent.yml
#
# Library modules are in scripts/lib/:
#   logging.sh    — log_step, log_ok, log_err, error trap
#   github-api.sh — status(), handle_error()
#   branch.sh     — branch_name(), RELEASE_VERSION, RELEASE_TARGET
#   prompts.sh    — write_initial_prompt(), write_resume_prompt(),
#                   capture_conversation_id(), extract_summary_json(),
#                   register_session()
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/logging.sh"
source "$SCRIPT_DIR/lib/github-api.sh"
source "$SCRIPT_DIR/lib/branch.sh"
source "$SCRIPT_DIR/lib/prompts.sh"

# --- Paths ---
AGENT_STATE_DIR="$HOME/.claude-agent/issues"
SHARED_GRADLE_HOME="$HOME/.gradle-agent"
AGENT_WORKSPACE_DIR="$HOME/agent-workspace"

# Source machine-specific config (not in repo — created during runner setup)
if [ -f "$HOME/.claude-agent/config" ]; then
  # shellcheck source=/dev/null
  source "$HOME/.claude-agent/config"
fi

# Sensible defaults if config didn't set them
CLAUDE_BIN="${CLAUDE_BIN:-claude}"
CLAUDE_PROJECTS_DIR="${CLAUDE_PROJECTS_DIR:-$HOME/.claude/projects}"

# Export API config for Claude Code (secret token is set by the workflow)
export ANTHROPIC_AUTH_TOKEN="${ANTHROPIC_AUTH_TOKEN:-}"
export ANTHROPIC_BASE_URL="${ANTHROPIC_BASE_URL:-}"
export ANTHROPIC_MODEL="${ANTHROPIC_MODEL:-}"
export ANTHROPIC_SMALL_FAST_MODEL="${ANTHROPIC_SMALL_FAST_MODEL:-}"
export API_TIMEOUT_MS="${API_TIMEOUT_MS:-}"
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC="${CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:-}"
export CLAUDE_CODE_DISABLE_NONSTREAMING_FALLBACK="${CLAUDE_CODE_DISABLE_NONSTREAMING_FALLBACK:-}"

# --- Environment (set by GitHub Actions) ---
ISSUE_NUM="${ISSUE_NUMBER:?}"
EVENT="${GITHUB_EVENT_NAME:?}"
REPO="${GITHUB_REPOSITORY:-andersonlucasg3/dilink-auto-android}"
RUN_ID="${GITHUB_RUN_ID:-unknown}"
SERVER_URL="${GITHUB_SERVER_URL:-https://github.com}"

mkdir -p "$AGENT_STATE_DIR"
mkdir -p "$SHARED_GRADLE_HOME"

export GRADLE_USER_HOME="$SHARED_GRADLE_HOME"

STATE_FILE="$AGENT_STATE_DIR/issue-${ISSUE_NUM}.json"

# --- Git identity ---
git config user.email "agent@dilink-auto.local"
git config user.name "DiLink-Auto Agent"
# Prevent line-ending conversion (repo is Windows/CRLF, runner is Linux/LF)
git config core.autocrlf false


# Configure git push authentication via GITHUB_TOKEN (runs before cd)

# Resolve api.github.com IP once (gh CLI's Go resolver fails on WSL DNS)
_resolve_github_api_ip() {
  local ip
  ip=$(getent hosts api.github.com 2>/dev/null | awk '{print $1; exit}')
  if [ -z "$ip" ]; then
    for ip in 140.82.121.5 140.82.121.6 4.228.31.149 4.228.31.150; do
      if curl -s --max-time 3 --resolve "api.github.com:443:$ip" "https://api.github.com/zen" >/dev/null 2>&1; then
        echo "$ip"; return 0
      fi
    done
    echo ""
  else
    echo "$ip"
  fi
}

GITHUB_API_IP="${GITHUB_API_IP:-}"
if [ -z "$GITHUB_API_IP" ]; then
  GITHUB_API_IP=$(_resolve_github_api_ip)
  [ -n "$GITHUB_API_IP" ] && log_ok "api.github.com → $GITHUB_API_IP" || log_err "Cannot resolve api.github.com"
fi

# Make an authenticated GitHub API call (replaces gh CLI which can't resolve DNS)
github_api() {
  local method="$1" path="$2" body="$3"
  local url="https://api.github.com${path}"
  local curl_opts=(-s -X "$method" --max-time 30)
  curl_opts+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
  curl_opts+=(-H "Accept: application/vnd.github+json")
  curl_opts+=(-H "Content-Type: application/json")
  [ -n "$GITHUB_API_IP" ] && curl_opts+=(--resolve "api.github.com:443:${GITHUB_API_IP}")
  if [ -n "$body" ]; then
    echo "$body" | curl "${curl_opts[@]}" -d @- "$url" 2>/dev/null
  else
    curl "${curl_opts[@]}" "$url" 2>/dev/null
  fi
}

# Fetch all pending user comments since the last processed one.
# Sets PENDING_COMMENTS (array of "id|body") and NEW_LAST_ID.
get_pending_comments() {
  local last_id="${1:-0}"
  PENDING_COMMENTS=""
  NEW_LAST_ID="$last_id"

  local resp
  resp=$(github_api GET "/repos/${REPO}/issues/${ISSUE_NUM}/comments?per_page=100&sort=created&direction=asc")
  if [ -z "$resp" ]; then
    echo "[comments] Failed to fetch comments — falling back to trigger comment only"
    return 1
  fi

  # Filter: user comments (not github-actions bot), with id > last_id
  local filtered
  filtered=$(echo "$resp" | jq -r --argjson last "$last_id" \
    '.[] | select(.id > $last) | select(.user.login != "github-actions") | "\(.id)|\(.body)"' 2>/dev/null)

  if [ -z "$filtered" ]; then
    echo "[comments] No pending user comments found after id=$last_id"
    return 1
  fi

  PENDING_COMMENTS="$filtered"
  NEW_LAST_ID=$(echo "$filtered" | tail -1 | cut -d'|' -f1)
  local count=$(echo "$filtered" | wc -l | tr -d ' ')
  echo "[comments] Found $count pending user comment(s), last_id=$last_id → new_last_id=$NEW_LAST_ID"
  return 0
}

# Helpers are now in scripts/lib/ — sourced at the top of this script
# --- Main ---
echo "=========================================="
echo " DiLink-Auto Issue Agent"
log_step "Event: $EVENT | Issue: #$ISSUE_NUM"
echo "=========================================="

BRANCH=$(branch_name)
if [ -n "$RELEASE_VERSION" ]; then
  echo " Type:   release → $RELEASE_TARGET (v$RELEASE_VERSION)"
else
  case "$BRANCH" in
    hotfix/*)   echo " Type:   hotfix → $RELEASE_TARGET" ;;
    fix/*)      echo " Type:   bug fix → $RELEASE_TARGET" ;;
    feature/*)  echo " Type:   feature → $RELEASE_TARGET" ;;
    docs/*)     echo " Type:   docs → $RELEASE_TARGET" ;;
    investigate/*) echo " Type:   investigation → $RELEASE_TARGET" ;;
    *)          echo " Type:   $BRANCH → $RELEASE_TARGET" ;;
  esac
fi

# Set up branch — reuse if it exists (resume), create fresh if new.
# For hotfix and release, the base branch is main; otherwise develop.
BASE_BRANCH="${RELEASE_TARGET:-develop}"
git fetch origin "$BASE_BRANCH" "$BRANCH" 2>/dev/null || true

# Discard ALL leftover changes — even CRLF conversions from .gitattributes
git reset --hard HEAD 2>/dev/null || true
git clean -ffdx -e '.gradle' 2>/dev/null || true

# Prune remote-deleted tags so stale local tags don't get pushed
git fetch origin --prune --prune-tags 2>/dev/null || true

# Set up branch
git fetch origin "$BASE_BRANCH" "$BRANCH" 2>/dev/null || true
if git rev-parse --verify "origin/$BRANCH" >/dev/null 2>&1; then
  log_step "Reusing branch: $BRANCH (base: $BASE_BRANCH)"
  git checkout -f "$BRANCH" 2>/dev/null || git checkout -f -b "$BRANCH" "origin/$BRANCH"
  git pull origin "$BRANCH" 2>/dev/null || true
  # Merge base branch to get .gitattributes and prevent CRLF/LF churn
  git merge "origin/$BASE_BRANCH" --no-edit 2>/dev/null || git checkout -f "origin/$BASE_BRANCH" -- .gitattributes 2>/dev/null || true
else
  log_step "Creating branch: $BRANCH (from: $BASE_BRANCH)"
  git checkout -f "$BASE_BRANCH"
  git pull origin "$BASE_BRANCH"
  git branch -D "$BRANCH" 2>/dev/null || true
  git checkout -f -b "$BRANCH"
fi

# Clone to a fixed path so Claude Code sees the same project directory
# regardless of which runner executes the job. Worktrees don't work because
# their .git is a file (not a dir), which Claude Code 2.1.121 can't resolve.
FIXED_WORKSPACE="$AGENT_WORKSPACE_DIR/issue-${ISSUE_NUM}"
if [ -d "$FIXED_WORKSPACE" ]; then
  log_step "Workspace: updating clone at $FIXED_WORKSPACE"
  git -C "$FIXED_WORKSPACE" fetch origin develop 2>/dev/null || true
  git -C "$FIXED_WORKSPACE" checkout -f develop 2>/dev/null || true
  git -C "$FIXED_WORKSPACE" reset --hard "origin/develop" 2>/dev/null || true
  git -C "$FIXED_WORKSPACE" checkout -f -b "$BRANCH" 2>/dev/null || git -C "$FIXED_WORKSPACE" checkout -f "$BRANCH" 2>/dev/null || true
else
  echo "[workspace] Creating new clone at $FIXED_WORKSPACE"
  mkdir -p "$AGENT_WORKSPACE_DIR"
  git clone --branch "$BRANCH" "file://$(pwd -P)" "$FIXED_WORKSPACE" 2>/dev/null || \
    git clone "https://github.com/${REPO}.git" --branch "$BRANCH" "$FIXED_WORKSPACE"
fi
cd "$FIXED_WORKSPACE" || exit 1
echo "[workspace] Working directory: $(pwd -P)"

# Fix git remote — the clone may have a stale file:// origin or expired token.
# Prefer PUSH_TOKEN (PAT) for git push — it triggers downstream workflows.
# GITHUB_TOKEN pushes do NOT trigger other workflows (by GitHub design).
PUSH_TOKEN="${PUSH_TOKEN:-}"
GH_TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
if [ -n "$PUSH_TOKEN" ]; then
  PUSH_AUTH_TOKEN="$PUSH_TOKEN"
  log_step "Using PUSH_TOKEN for git (triggers workflows): ${#PUSH_TOKEN}chars"
else
  PUSH_AUTH_TOKEN="$GH_TOKEN"
  log_step "Using GITHUB_TOKEN for git (will NOT trigger downstream workflows): ${#PUSH_AUTH_TOKEN}chars"
fi
if [ -n "$PUSH_AUTH_TOKEN" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
  git remote set-url origin "https://x-access-token:${PUSH_AUTH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" || log_err "remote set-url failed"
  log_ok "git remote updated"
else
  log_err "No auth token available — push will fail"
fi

# Record which conversations exist before the run (to detect the new one)
BEFORE_JSONLS=$(find "$CLAUDE_PROJECTS_DIR" -name '*.jsonl' -type f 2>/dev/null | grep -v '/subagents/' | sort || true)

# Read status comment ID (set by status() calls above) for agent prompt
STATUS_COMMENT_ID=$(jq -r '.status_comment_id // ""' "$STATE_FILE" 2>/dev/null || echo "")

log_step "EVENT=$EVENT ISSUE=$ISSUE_NUM STATE_FILE=$STATE_FILE"

# --- Run Claude Code ---
if [ "$EVENT" = "issues" ]; then
  echo "--- New issue: starting fresh conversation ---"
  jq -n --arg issue "$ISSUE_NUM" --arg title "$ISSUE_TITLE" '{issue_number: $issue, title: $title}' > "$STATE_FILE"
  status "🔍 Analyzing request..."
  STATUS_COMMENT_ID=$(jq -r '.status_comment_id // ""' "$STATE_FILE" 2>/dev/null || echo "")
  write_initial_prompt

  echo "--- Starting Claude Code (new conversation) ---"
  AGENT_SESSION_ID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen 2>/dev/null || echo "issue-${ISSUE_NUM}-$(date +%s)")
  log_step "Claude: $CLAUDE_BIN --session-id $AGENT_SESSION_ID"
  set +e
  OUTPUT=$(timeout 7200 $CLAUDE_BIN --dangerously-skip-permissions --session-id "$AGENT_SESSION_ID" -p "Start by reading /tmp/agent-prompt-${ISSUE_NUM}.txt and complete the task described there." 2>&1)
  CLAUDE_EXIT=$?
  set -e
  echo "--- Claude Code output ---"
  echo "$OUTPUT"
  echo "--- Claude Code finished (exit=$CLAUDE_EXIT) ---"
  if [ "$CLAUDE_EXIT" -ne 0 ]; then
    handle_error "Claude Code exited with code $CLAUDE_EXIT" "Prompt: /tmp/agent-prompt-${ISSUE_NUM}.txt"
  fi

  CONV_ID=$(capture_conversation_id "$OUTPUT")
  if [ -z "$CONV_ID" ]; then
    AFTER_JSONLS=$(find "$CLAUDE_PROJECTS_DIR" -name '*.jsonl' -type f 2>/dev/null | grep -v '/subagents/' | sort || true)
    NEW_JSONL=$(comm -13 <(echo "$BEFORE_JSONLS") <(echo "$AFTER_JSONLS") | head -1 || true)
    if [ -n "$NEW_JSONL" ]; then
      CONV_ID=$(basename "$NEW_JSONL" .jsonl)
    fi
  fi
  echo "Conversation ID: ${CONV_ID:-unknown}"

  SUMMARY_JSON=$(extract_summary_json "$OUTPUT")
  echo "Summary: $SUMMARY_JSON"

  # Save state with per-issue session ID
  if [ -n "$AGENT_SESSION_ID" ] && [ "${#AGENT_SESSION_ID}" -ge 20 ]; then
    _existing_sid=$(jq -r '.status_comment_id // ""' "$STATE_FILE" 2>/dev/null || echo "")
    jq -n \
      --arg sid "$AGENT_SESSION_ID" \
      --arg branch "$BRANCH" \
      --arg issue "$ISSUE_NUM" \
      --arg title "$ISSUE_TITLE" \
      --arg csid "$_existing_sid" \
      '{session_id: $sid, branch: $branch, issue_number: $issue, title: $title, status_comment_id: $csid, last_processed_comment_id: 0}' \
      > "$STATE_FILE"
    echo "State saved to $STATE_FILE (session=$AGENT_SESSION_ID)"
    register_session "$AGENT_SESSION_ID"
  else
    echo "WARNING: Invalid session_id ($AGENT_SESSION_ID) — not saving state"
  fi

  # Commit and push if changes were made
  COMMIT_SHA=""
  git add -A
  if ! git diff --quiet --cached; then
    git diff --cached --stat
    git commit -m "$(echo "$SUMMARY_JSON" | jq -r '"Agent: \(.summary)"')" || true
  fi
  # Push if local differs from remote (agent may have committed without pushing)
  PUSH_OK=true
  if git rev-parse "origin/$BRANCH" >/dev/null 2>&1; then
    if [ "$(git rev-parse HEAD)" != "$(git rev-parse "origin/$BRANCH")" ]; then
      git push origin "$BRANCH" || PUSH_OK=false
    fi
  else
    git push -u origin "$BRANCH" || PUSH_OK=false
  fi
  COMMIT_SHA=$(git rev-parse --short HEAD)

  # Always build after the agent finishes (don't trust agent's build claim)
  echo "--- Building APK ---"
  status "🔨 Building APK..."
  rm -f app-client/build/outputs/apk/debug/app-client-debug.apk
  chmod +x gradlew 2>/dev/null || true
  sed -i 's/\r$//' gradlew 2>/dev/null || true
  export JAVA_HOME="${JAVA_HOME_17_X64:-$JAVA_HOME}"
  export PATH="${JAVA_HOME}/bin:$PATH"
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  echo "JAVA_HOME=$JAVA_HOME"
  echo "ANDROID_HOME=$ANDROID_HOME"
  APK_BUILT=false
  if ./gradlew :app-client:assembleDebug 2>&1 | tail -5; then
    if [ -f "app-client/build/outputs/apk/debug/app-client-debug.apk" ]; then
      APK_BUILT=true
    fi
  fi

  # Post single summary comment
  SUMMARY_TEXT=$(echo "$SUMMARY_JSON" | jq -r '.summary')

  cat > /tmp/summary-comment.md << EOFCOMMENT
## 🤖 Agent Investigation — Issue #${ISSUE_NUM}

**Branch:** [\`${BRANCH}\`](${SERVER_URL}/${REPO}/tree/${BRANCH})${COMMIT_SHA:+ (\`${COMMIT_SHA}\`)}${PR_URL:+  |  **PR:** [${PR_URL}](${PR_URL})}

${SUMMARY_TEXT}

EOFCOMMENT

  if [ "$APK_BUILT" = true ]; then
    cat >> /tmp/summary-comment.md << EOFCOMMENT
### Build
✅ APK built — [download](${SERVER_URL}/${REPO}/actions/runs/${RUN_ID})

EOFCOMMENT
  else
    cat >> /tmp/summary-comment.md << EOFCOMMENT
### Build
❌ Build failed — check the [workflow run](${SERVER_URL}/${REPO}/actions/runs/${RUN_ID})

EOFCOMMENT
  fi

  if [ "$PUSH_OK" = true ]; then
    cat >> /tmp/summary-comment.md << EOFCOMMENT
### Push
✅ Pushed to [\`${BRANCH}\`](${SERVER_URL}/${REPO}/tree/${BRANCH})${COMMIT_SHA:+ (\`${COMMIT_SHA}\`)}

EOFCOMMENT
  else
    cat >> /tmp/summary-comment.md << EOFCOMMENT
### Push
❌ Push failed — changes are committed locally but not on GitHub

EOFCOMMENT
  fi

  cat >> /tmp/summary-comment.md << 'EOFCOMMENT'
---
Reply to this issue to continue. The agent will pick up from where it left off.
EOFCOMMENT

  status "$(cat /tmp/summary-comment.md)"

  # Handle agent-requested actions
  ACTION=$(echo "$SUMMARY_JSON" | jq -r '.action // "none"')
  if [ "$ACTION" = "close" ]; then
    echo "[action] Closing issue #$ISSUE_NUM per agent request"
    github_api PATCH "/repos/${REPO}/issues/${ISSUE_NUM}" '{"state":"closed"}' > /dev/null || true
    rm -f "$STATE_FILE" 2>/dev/null || true
  elif [ "$ACTION" = "pr" ]; then
    echo "[action] Creating pull request for $BRANCH → $RELEASE_TARGET"
    PR_RESPONSE=$(github_api POST "/repos/${REPO}/pulls" "{\"title\":\"${ISSUE_TITLE}\",\"head\":\"${BRANCH}\",\"base\":\"${RELEASE_TARGET}\",\"body\":\"Closes #${ISSUE_NUM}\"}")
    PR_URL=$(echo "$PR_RESPONSE" | jq -r '.html_url // ""' 2>/dev/null || true)
    if [ -n "$PR_URL" ] && [ "$PR_URL" != "null" ]; then
      echo "PR created: $PR_URL"
    else
      echo "PR creation failed — response: $(echo "$PR_RESPONSE" | head -3)"
    fi
  fi

elif [ "$EVENT" = "issue_comment" ]; then
  echo "--- Issue comment: resuming or starting conversation ---"

  # Fetch all pending user comments since the last processed one.
  # This solves the problem where GitHub concurrency cancels queued runs.
  LAST_PROCESSED_ID=$(jq -r '.last_processed_comment_id // 0' "$STATE_FILE" 2>/dev/null || echo "0")
  if [ "$LAST_PROCESSED_ID" = "null" ]; then LAST_PROCESSED_ID=0; fi

  COMBINED_COMMENT=""
  if get_pending_comments "$LAST_PROCESSED_ID"; then
    # Build combined comment from all pending
    _pending_count=0
    while IFS='|' read -r cid cbody; do
      _pending_count=$((_pending_count + 1))
      if [ -z "$COMBINED_COMMENT" ]; then
        COMBINED_COMMENT="$cbody"
      else
        COMBINED_COMMENT="$COMBINED_COMMENT

---
**Next request (comment #$cid):**

$cbody"
      fi
    done <<< "$PENDING_COMMENTS"
    echo "[comments] Combined $_pending_count comment(s) into single prompt"
    # Use the last (newest) comment ID as the effective trigger
    if [ -n "$NEW_LAST_ID" ] && [ "$NEW_LAST_ID" != "0" ]; then
      COMMENT_ID="$NEW_LAST_ID"
    fi
  else
    # Fallback: use only the triggering comment
    COMBINED_COMMENT="$COMMENT_BODY"
    echo "[comments] Using trigger comment only (id=$COMMENT_ID)"
  fi

  # Determine if we can resume a prior session
  SESSION_ID=""
  if [ -f "$STATE_FILE" ]; then
    SESSION_ID=$(jq -r '.session_id // .conversation_id // ""' "$STATE_FILE")
    if [ "$SESSION_ID" = "null" ] || [ "$SESSION_ID" = "." ] || [ "${#SESSION_ID}" -lt 20 ]; then
      echo "Invalid session_id in state ($SESSION_ID) — clearing"
      rm -f "$STATE_FILE"
      SESSION_ID=""
    fi
  fi

  # Try resume if we have a valid session
  if [ -n "$SESSION_ID" ]; then
    log_step "Resuming session: $SESSION_ID"

    # Clear old status_comment_id so this run gets its own status comment.
    if [ -f "$STATE_FILE" ]; then
      if ! jq 'del(.status_comment_id)' "$STATE_FILE" > "${STATE_FILE}.tmp" 2>/dev/null; then
        echo "WARNING: jq del(.status_comment_id) failed — removing corrupt state file"
        rm -f "$STATE_FILE"
      else
        mv "${STATE_FILE}.tmp" "$STATE_FILE"
      fi
    fi

    status "🔄 Continuing..."
    STATUS_COMMENT_ID=$(jq -r '.status_comment_id // ""' "$STATE_FILE" 2>/dev/null || echo "")

    write_initial_prompt
    write_resume_prompt "$COMBINED_COMMENT"

    cat > "/tmp/resume-msg-${ISSUE_NUM}.txt" << EOFMSG
The user posted the following request(s) on issue #${ISSUE_NUM}:

${COMBINED_COMMENT}

Start by reading /tmp/agent-prompt-${ISSUE_NUM}.txt for full context, then complete ALL of the user's requests above, in order.
EOFMSG
    log_step "Claude: $CLAUDE_BIN --resume $SESSION_ID"
    echo "[resume prompt] $(cat /tmp/resume-msg-${ISSUE_NUM}.txt | head -1)"
    set +e
    OUTPUT=$(timeout 600 $CLAUDE_BIN --dangerously-skip-permissions --resume "$SESSION_ID" -p "$(cat /tmp/resume-msg-${ISSUE_NUM}.txt)" 2>&1)
    CLAUDE_EXIT=$?
    set -e
    echo "--- Claude output (resume) ---"
    echo "$OUTPUT"
    if [ "$CLAUDE_EXIT" -ne 0 ]; then
      echo "--- Resume failed (exit $CLAUDE_EXIT), falling through to fresh start ---"
      rm -f "$STATE_FILE"
      SESSION_ID=""
    else
      echo "--- Claude Code resumed successfully ---"
    fi
  fi

  # Fresh start: no valid state, or resume failed
  if [ -z "$SESSION_ID" ]; then
    log_step "Fresh start for issue comment"
    jq -n --arg issue "$ISSUE_NUM" --arg title "$ISSUE_TITLE" '{issue_number: $issue, title: $title}' > "$STATE_FILE"
    status "🔍 Analyzing request..."
    STATUS_COMMENT_ID=$(jq -r '.status_comment_id // ""' "$STATE_FILE" 2>/dev/null || echo "")
    log_step "STATUS_COMMENT_ID=$STATUS_COMMENT_ID"
    write_initial_prompt
    write_resume_prompt "$COMBINED_COMMENT"

    AGENT_SESSION_ID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen 2>/dev/null || echo "issue-${ISSUE_NUM}-$(date +%s)")
    log_step "Claude: $CLAUDE_BIN --session-id $AGENT_SESSION_ID"
    set +e
    OUTPUT=$(timeout 7200 $CLAUDE_BIN --dangerously-skip-permissions --session-id "$AGENT_SESSION_ID" -p "Start by reading /tmp/agent-prompt-${ISSUE_NUM}.txt and complete the task described there." 2>&1)
    CLAUDE_EXIT=$?
    set -e
    echo "--- Claude output (fresh) ---"
    echo "$OUTPUT"
    if [ "$CLAUDE_EXIT" -ne 0 ]; then
      handle_error "Claude Code exited with code $CLAUDE_EXIT"
    fi
    echo "--- Claude Code finished ---"

    _existing_sid=$(jq -r '.status_comment_id // ""' "$STATE_FILE" 2>/dev/null || echo "")
    jq -n \
      --arg sid "${AGENT_SESSION_ID:-unknown}" \
      --arg branch "$BRANCH" \
      --arg issue "$ISSUE_NUM" \
      --arg title "$ISSUE_TITLE" \
      --arg csid "$_existing_sid" \
      '{session_id: $sid, branch: $branch, issue_number: $issue, title: $title, status_comment_id: $csid, last_processed_comment_id: 0}' \
      > "$STATE_FILE"
    register_session "${AGENT_SESSION_ID:-unknown}"
  fi

  SUMMARY_JSON=$(extract_summary_json "$OUTPUT")
  echo "Summary: $SUMMARY_JSON"

  # Commit if changes
  COMMIT_SHA=""
  if ! git diff --quiet || ! git diff --cached --quiet; then
    git add -A
    git diff --cached --stat
    git commit -m "$(echo "$SUMMARY_JSON" | jq -r '"Agent follow-up: \(.summary)"')" || true
    git push origin "$BRANCH" || echo "Warning: push failed (non-fatal)"
    COMMIT_SHA=$(git rev-parse --short HEAD)
  fi

  # Always build after the agent finishes (don't trust agent's build claim)
  echo "--- Building APK ---"
  status "🔨 Building APK..."
  rm -f app-client/build/outputs/apk/debug/app-client-debug.apk
  chmod +x gradlew 2>/dev/null || true
  sed -i 's/\r$//' gradlew 2>/dev/null || true
  export JAVA_HOME="${JAVA_HOME_17_X64:-$JAVA_HOME}"
  export PATH="${JAVA_HOME}/bin:$PATH"
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  echo "JAVA_HOME=$JAVA_HOME"
  echo "ANDROID_HOME=$ANDROID_HOME"
  APK_BUILT=false
  if ./gradlew :app-client:assembleDebug 2>&1 | tail -5; then
    if [ -f "app-client/build/outputs/apk/debug/app-client-debug.apk" ]; then
      APK_BUILT=true
    fi
  fi

  SUMMARY_TEXT=$(echo "$SUMMARY_JSON" | jq -r '.summary')

  cat > /tmp/summary-comment.md << EOFCOMMENT
## 🤖 Agent Update — Issue #${ISSUE_NUM}

**Branch:** [\`${BRANCH}\`](${SERVER_URL}/${REPO}/tree/${BRANCH})${COMMIT_SHA:+ (\`${COMMIT_SHA}\`)}${PR_URL:+  |  **PR:** [${PR_URL}](${PR_URL})}

${SUMMARY_TEXT}

EOFCOMMENT

  if [ "$APK_BUILT" = true ]; then
    cat >> /tmp/summary-comment.md << EOFCOMMENT
### Build
✅ APK built — [download](${SERVER_URL}/${REPO}/actions/runs/${RUN_ID})

EOFCOMMENT
  else
    cat >> /tmp/summary-comment.md << EOFCOMMENT
### Build
❌ Build failed — check the [workflow run](${SERVER_URL}/${REPO}/actions/runs/${RUN_ID})

EOFCOMMENT
  fi

  cat >> /tmp/summary-comment.md << 'EOFCOMMENT'
---
Reply to continue. The agent resumes its conversation and picks up where it left off.
EOFCOMMENT

  status "$(cat /tmp/summary-comment.md)"

  # Handle agent-requested actions
  ACTION=$(echo "$SUMMARY_JSON" | jq -r '.action // "none"')
  if [ "$ACTION" = "close" ]; then
    echo "[action] Closing issue #$ISSUE_NUM per agent request"
    github_api PATCH "/repos/${REPO}/issues/${ISSUE_NUM}" '{"state":"closed"}' > /dev/null || true
    rm -f "$STATE_FILE" 2>/dev/null || true
  elif [ "$ACTION" = "pr" ]; then
    echo "[action] Creating pull request for $BRANCH → $RELEASE_TARGET"
    PR_RESPONSE=$(github_api POST "/repos/${REPO}/pulls" "{\"title\":\"${ISSUE_TITLE}\",\"head\":\"${BRANCH}\",\"base\":\"${RELEASE_TARGET}\",\"body\":\"Closes #${ISSUE_NUM}\"}")
    PR_URL=$(echo "$PR_RESPONSE" | jq -r '.html_url // ""' 2>/dev/null || true)
    if [ -n "$PR_URL" ] && [ "$PR_URL" != "null" ]; then
      echo "PR created: $PR_URL"
    else
      echo "PR creation failed — response: $(echo "$PR_RESPONSE" | head -3)"
    fi
  fi

  # Save last processed comment ID so next run only fetches newer comments
  if [ -f "$STATE_FILE" ] && [ -n "${NEW_LAST_ID:-}" ] && [ "$NEW_LAST_ID" != "${LAST_PROCESSED_ID:-0}" ]; then
    jq --arg lid "$NEW_LAST_ID" '. + {last_processed_comment_id: $lid}' "$STATE_FILE" > "${STATE_FILE}.tmp" 2>/dev/null && \
      mv "${STATE_FILE}.tmp" "$STATE_FILE" || true
    echo "[comments] Updated last_processed_comment_id=$NEW_LAST_ID"
  fi
fi

echo "=========================================="
echo " Done"
echo "=========================================="
