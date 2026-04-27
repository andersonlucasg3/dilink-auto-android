#!/bin/bash
set -euo pipefail

# ============================================================
# DiLink-Auto Issue Agent
# Orchestrates Claude Code to work on GitHub issues autonomously.
# Triggered by .github/workflows/issue-agent.yml
# ============================================================

# --- Paths ---
AGENT_STATE_DIR="$HOME/.claude-agent/issues"
SHARED_GRADLE_HOME="$HOME/.gradle-agent"

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

# --- Helpers ---

# Add an emoji reaction to the triggering comment (or issue if no comment)
# Usage: react eyes | react rocket | react heart | react confused
react() {
  local content="$1"
  local list_target delete_url
  if [ -n "${COMMENT_ID:-}" ]; then
    list_target="repos/$REPO/issues/comments/$COMMENT_ID/reactions"
    delete_url="repos/$REPO/issues/comments/$COMMENT_ID/reactions"
  else
    list_target="repos/$REPO/issues/$ISSUE_NUM/reactions"
    delete_url="repos/$REPO/issues/$ISSUE_NUM/reactions"
  fi

  echo "[reaction] Setting :${content}: on ${list_target}"

  # Remove any previous reaction by us before adding the new one
  local prev_id
  prev_id=$(timeout 10 gh api "$list_target" --jq '.[] | select(.user.login == "andersonlucasg3") | .id' 2>/dev/null | head -1)
  if [ -n "$prev_id" ]; then
    timeout 10 gh api "${delete_url}/${prev_id}" --method DELETE --silent 2>/dev/null || true
  fi

  timeout 10 gh api "$list_target" -f content="$content" --silent 2>/dev/null || true
}

post_comment() {
  local body="$1"
  local tmpfile="/tmp/comment-body-${ISSUE_NUM}.txt"
  echo "$body" > "$tmpfile"
  jq -n --rawfile body "$tmpfile" '{body: $body}' |
    curl -s -X POST \
      -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      "https://api.github.com/repos/${REPO}/issues/${ISSUE_NUM}/comments" \
      -d @- > /dev/null
  rm -f "$tmpfile"
}

handle_error() {
  local err_msg="${1:-Unknown error}"
  local details="${2:-}"
  react confused
  cat > /tmp/error-comment.txt << EOF
## 🤖 Agent Error

\`\`\`
${err_msg}
\`\`\`

${details}

[Workflow run](${SERVER_URL}/${REPO}/actions/runs/${RUN_ID})
EOF
  post_comment "$(cat /tmp/error-comment.txt)"
  exit 1
}

# Find the conversation ID from Claude Code's output or filesystem
capture_conversation_id() {
  local output="$1"
  local cid

  # Try parsing from stdout
  cid=$(echo "$output" | grep -oiP 'conversation[_-]?\s*(id)?:\s*\K[a-f0-9-]{20,}' | head -1 || true)

  # Fallback: newest .jsonl across ALL project directories
  if [ -z "$cid" ] && [ -d "$CLAUDE_PROJECTS_DIR" ]; then
    cid=$(find "$CLAUDE_PROJECTS_DIR" -name '*.jsonl' -type f 2>/dev/null | xargs ls -t 2>/dev/null | head -1 | xargs basename | sed 's/\.jsonl$//' || true)
  fi

  echo "$cid"
}

# Extract the summary JSON block from Claude Code's response
extract_summary_json() {
  local output="$1"
  local json
  json=$(echo "$output" | sed -n '/```json/,/```/p' | sed '1d;$d' || true)
  if [ -z "$json" ]; then
    json='{"summary":"Agent finished but no JSON summary block found in the output.","changes_made":false,"build_success":false,"action":"none"}'
  fi
  echo "$json"
}

branch_name() {
  echo "issue/${ISSUE_NUM}-agent"
}

# --- Prompt builders ---
# Each writes to /tmp/agent-prompt-${ISSUE_NUM}.txt to avoid shell escaping issues

write_initial_prompt() {
  cat > /tmp/agent-prompt-${ISSUE_NUM}.txt << 'ENDPROMPT'
You are an autonomous development agent for **DiLink-Auto** — an open-source Android Auto alternative for BYD DiLink 3.0+ cars. Phone apps run on a virtual display, encode as H.264 video, and stream to the car over WiFi TCP. Touch events flow back from car to phone.

Read all docs in docs/*.md before starting.
Build with: `./gradlew :app-client:assembleDebug`

CRITICAL: Do NOT use gh CLI or post comments via GitHub API. The script handles all GitHub interaction (comments, reactions, commits, push).

ENDPROMPT

  cat >> /tmp/agent-prompt-${ISSUE_NUM}.txt << ENDPROMPT
## GitHub Issue #${ISSUE_NUM}: ${ISSUE_TITLE}

${ISSUE_BODY}

## After Finishing
1. Build the APK to verify compilation
2. If the build fails, fix and rebuild until it passes
3. Output this JSON block as the VERY LAST thing.
   The "summary" field must use markdown with clear sections
   (## What was done, ## What needs testing, ## Build).

\`\`\`json
{"summary": "...", "changes_made": true, "build_success": true, "action": "none"}
\`\`\`
\`\`\`

Set "action" to "close" if the user asked to close the issue and the work is complete.
ENDPROMPT
}

write_resume_prompt() {
  local comment="$1"

  cat >> /tmp/agent-prompt-${ISSUE_NUM}.txt << ENDPROMPT
## Follow-Up Comment

${comment}

CRITICAL: Do NOT use gh CLI or post comments via GitHub API. The script handles all GitHub interaction (comments, reactions, commits, push).

## After Finishing
1. Review previous changes on this branch with \`git diff HEAD~1\`
2. If you make code changes, build: \`./gradlew :app-client:assembleDebug\`
3. If the build fails, fix and rebuild
4. Output this JSON block as the VERY LAST thing.
   The "summary" field must use markdown with clear sections.

\`\`\`json
{"summary": "...", "changes_made": true, "build_success": true, "action": "none"}
\`\`\`

Set "action" to "close" if the user asked to close the issue and the work is complete.
ENDPROMPT
}

# --- Main ---
echo "=========================================="
echo " DiLink-Auto Issue Agent"
echo " Event:  $EVENT"
echo " Issue:  #$ISSUE_NUM"
echo "=========================================="

BRANCH=$(branch_name)

# Set up branch
git fetch origin develop
git checkout develop
git pull origin develop
git branch -D "$BRANCH" 2>/dev/null || true
git checkout -b "$BRANCH"

# Record which .jsonl files exist before the run (to detect the new one)
BEFORE_JSONLS=$(find "$CLAUDE_PROJECTS_DIR" -name '*.jsonl' -type f 2>/dev/null | sort || true)

# --- Run Claude Code ---
if [ "$EVENT" = "issues" ]; then
  echo "--- New issue: starting fresh conversation ---"
  write_initial_prompt

  echo "--- Starting Claude Code (new conversation) ---"
  react eyes
  set +e
  OUTPUT=$($CLAUDE_BIN --dangerously-skip-permissions -p "Start by reading /tmp/agent-prompt-${ISSUE_NUM}.txt and complete the task described there." 2>&1)
  CLAUDE_EXIT=$?
  set -e
  if [ "$CLAUDE_EXIT" -ne 0 ]; then
    handle_error "Claude Code exited with code $CLAUDE_EXIT" "Prompt: /tmp/agent-prompt-${ISSUE_NUM}.txt"
  fi
  echo "--- Claude Code finished ---"

  CONV_ID=$(capture_conversation_id "$OUTPUT")
  if [ -z "$CONV_ID" ]; then
    # Try detecting new .jsonl
    AFTER_JSONLS=$(find "$CLAUDE_PROJECTS_DIR" -name '*.jsonl' -type f 2>/dev/null | sort || true)
    NEW_JSONL=$(comm -13 <(echo "$BEFORE_JSONLS") <(echo "$AFTER_JSONLS") | head -1 || true)
    if [ -n "$NEW_JSONL" ]; then
      CONV_ID=$(basename "$NEW_JSONL" .jsonl)
    fi
  fi
  echo "Conversation ID: ${CONV_ID:-unknown}"

  SUMMARY_JSON=$(extract_summary_json "$OUTPUT")
  echo "Summary: $SUMMARY_JSON"

  # Save state
  jq -n \
    --arg cid "$CONV_ID" \
    --arg branch "$BRANCH" \
    --arg issue "$ISSUE_NUM" \
    --arg title "$ISSUE_TITLE" \
    '{conversation_id: $cid, branch: $branch, issue_number: $issue, title: $title}' \
    > "$STATE_FILE"
  echo "State saved to $STATE_FILE"

  # Commit and push if changes were made
  CHANGES_MADE=$(echo "$SUMMARY_JSON" | jq -r '.changes_made // false')
  BUILD_SUCCESS=$(echo "$SUMMARY_JSON" | jq -r '.build_success // false')

  COMMIT_SHA=""
  if [ "$CHANGES_MADE" = "true" ]; then
    if ! git diff --quiet || ! git diff --cached --quiet; then
      git add -A
      git diff --cached --stat
      git commit -m "Agent: $(echo "$SUMMARY_JSON" | jq -r '.summary' | head -c 200)" || true
      git push origin "$BRANCH" || echo "Warning: push failed (non-fatal)"
      COMMIT_SHA=$(git rev-parse --short HEAD)
    fi
  fi

  # Post single summary comment
  SUMMARY_TEXT=$(echo "$SUMMARY_JSON" | jq -r '.summary')

  cat > /tmp/summary-comment.md << EOFCOMMENT
## 🤖 Agent Investigation — Issue #${ISSUE_NUM}

**Branch:** [\`${BRANCH}\`](../../tree/${BRANCH})${COMMIT_SHA:+ (\`${COMMIT_SHA}\`)}

${SUMMARY_TEXT}

EOFCOMMENT

  # Add build status
  if [ -f "app-client/build/outputs/apk/debug/app-client-debug.apk" ]; then
    cat >> /tmp/summary-comment.md << EOFCOMMENT
### Build
✅ APK built successfully — [download](${SERVER_URL}/${REPO}/actions/runs/${RUN_ID})

EOFCOMMENT
  elif [ "$BUILD_SUCCESS" = "false" ]; then
    cat >> /tmp/summary-comment.md << EOFCOMMENT
### Build
❌ Build failed — check the [workflow run](${SERVER_URL}/${REPO}/actions/runs/${RUN_ID})

EOFCOMMENT
  fi

  cat >> /tmp/summary-comment.md << 'EOFCOMMENT'
---
Reply to this issue to continue. The agent will pick up from where it left off.
EOFCOMMENT

  react heart
  post_comment "$(cat /tmp/summary-comment.md)"

  # Close the issue if the agent requested it
  if [ "$(echo "$SUMMARY_JSON" | jq -r '.action // "none"')" = "close" ]; then
    echo "[action] Closing issue #$ISSUE_NUM per agent request"
    GH_TOKEN="$GITHUB_TOKEN" gh issue close "$ISSUE_NUM" 2>/dev/null || true
  fi

elif [ "$EVENT" = "issue_comment" ]; then
  echo "--- Issue comment: resuming or starting conversation ---"

  if [ -f "$STATE_FILE" ]; then
    CONV_ID=$(jq -r '.conversation_id' "$STATE_FILE")
    echo "Found prior state — resuming conversation: $CONV_ID"

    write_resume_prompt "$COMMENT_BODY"

    echo "--- Resuming Claude Code conversation: $CONV_ID ---"
    react eyes
    set +e
    OUTPUT=$($CLAUDE_BIN --dangerously-skip-permissions --resume "$CONV_ID" -p "Start by reading /tmp/agent-prompt-${ISSUE_NUM}.txt and complete the task described there." 2>&1)
    CLAUDE_EXIT=$?
    set -e
    if [ "$CLAUDE_EXIT" -ne 0 ]; then
      handle_error "Claude Code exited with code $CLAUDE_EXIT" "Conversation ID: $CONV_ID"
    fi
    echo "--- Claude Code finished ---"
  else
    echo "No prior state — treating as new for issue #$ISSUE_NUM"
    write_initial_prompt

    echo "--- Starting Claude Code (new conversation, no prior state) ---"
    react eyes
    set +e
    OUTPUT=$($CLAUDE_BIN --dangerously-skip-permissions -p "Start by reading /tmp/agent-prompt-${ISSUE_NUM}.txt and complete the task described there." 2>&1)
    CLAUDE_EXIT=$?
    set -e
    if [ "$CLAUDE_EXIT" -ne 0 ]; then
      handle_error "Claude Code exited with code $CLAUDE_EXIT"
    fi
    echo "--- Claude Code finished ---"

    CONV_ID=$(capture_conversation_id "$OUTPUT")
    if [ -z "$CONV_ID" ]; then
      AFTER_JSONLS=$(find "$CLAUDE_PROJECTS_DIR" -name '*.jsonl' -type f 2>/dev/null | sort || true)
      NEW_JSONL=$(comm -13 <(echo "$BEFORE_JSONLS") <(echo "$AFTER_JSONLS") | head -1 || true)
      if [ -n "$NEW_JSONL" ]; then
        CONV_ID=$(basename "$NEW_JSONL" .jsonl)
      fi
    fi

    jq -n \
      --arg cid "$CONV_ID" \
      --arg branch "$BRANCH" \
      --arg issue "$ISSUE_NUM" \
      --arg title "$ISSUE_TITLE" \
      '{conversation_id: $cid, branch: $branch, issue_number: $issue, title: $title}' \
      > "$STATE_FILE"
  fi

  SUMMARY_JSON=$(extract_summary_json "$OUTPUT")
  echo "Summary: $SUMMARY_JSON"

  # Commit if changes
  CHANGES_MADE=$(echo "$SUMMARY_JSON" | jq -r '.changes_made // false')

  COMMIT_SHA=""
  if [ "$CHANGES_MADE" = "true" ]; then
    if ! git diff --quiet || ! git diff --cached --quiet; then
      git add -A
      git diff --cached --stat
      git commit -m "Agent follow-up: $(echo "$SUMMARY_JSON" | jq -r '.summary' | head -c 200)" || true
      git push origin "$BRANCH" || echo "Warning: push failed (non-fatal)"
      COMMIT_SHA=$(git rev-parse --short HEAD)
    fi
  fi

  SUMMARY_TEXT=$(echo "$SUMMARY_JSON" | jq -r '.summary')

  cat > /tmp/summary-comment.md << EOFCOMMENT
## 🤖 Agent Update — Issue #${ISSUE_NUM}

**Branch:** [\`${BRANCH}\`](../../tree/${BRANCH})${COMMIT_SHA:+ (\`${COMMIT_SHA}\`)}

${SUMMARY_TEXT}

EOFCOMMENT

  if [ -f "app-client/build/outputs/apk/debug/app-client-debug.apk" ]; then
    cat >> /tmp/summary-comment.md << EOFCOMMENT
### Build
✅ APK built — [download](${SERVER_URL}/${REPO}/actions/runs/${RUN_ID})

EOFCOMMENT
  fi

  cat >> /tmp/summary-comment.md << 'EOFCOMMENT'
---
Reply to continue. The agent resumes its conversation and picks up where it left off.
EOFCOMMENT

  react heart
  post_comment "$(cat /tmp/summary-comment.md)"

  # Close the issue if the agent requested it
  if [ "$(echo "$SUMMARY_JSON" | jq -r '.action // "none"')" = "close" ]; then
    echo "[action] Closing issue #$ISSUE_NUM per agent request"
    GH_TOKEN="$GITHUB_TOKEN" gh issue close "$ISSUE_NUM" 2>/dev/null || true
  fi
fi

echo "=========================================="
echo " Done"
echo "=========================================="
