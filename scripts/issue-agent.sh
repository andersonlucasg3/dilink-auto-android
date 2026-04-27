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

post_comment() {
  local body="$1"
  echo "$body" | gh issue comment "$ISSUE_NUM" --body-file -
}

handle_error() {
  local err_msg="${1:-Unknown error}"
  local details="${2:-}"
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
    json='{"summary":"Agent finished but no JSON summary block found in the output.","changes_made":false,"build_success":false}'
  fi
  echo "$json"
}

branch_name() {
  echo "issue/${ISSUE_NUM}-agent"
}

# --- Prompt builders ---
# Each writes to /tmp/agent-prompt.txt to avoid shell escaping issues

write_initial_prompt() {
  cat > /tmp/agent-prompt.txt << 'ENDPROMPT'
You are an autonomous development agent working on the **DiLink-Auto** project — an open-source Android Auto alternative that mirrors phone apps onto BYD DiLink 3.0+ car screens. The phone runs apps on a virtual display, encodes the screen as H.264 video, and streams it to the car over WiFi TCP. Touches on the car screen are sent back to the phone as input events.

## Repository Structure
- **app-client/** — Phone APK. ConnectionService (3-port TCP relay), VirtualDisplayClient (VD server relay), UpdateManager (self-update), NotificationService, FileLog
- **app-server/** — Car APK. CarConnectionService (parallel WiFi+USB state machine), VideoDecoder (H.264 → TextureView), MirrorScreen, LauncherScreen, PersistentNavBar
- **vd-server/** — Standalone Java. VirtualDisplayServer (H.264 encoder, NIO write queue, Selector reader), SurfaceScaler (EGL/GLES GPU downscale), FakeContext
- **protocol/** — Shared Kotlin library. FrameCodec, Connection (NIO), Discovery (mDNS), UsbAdbConnection, NioReader
- **docs/** — README.md (overview), architecture.md (design decisions), protocol.md (wire format), client.md (phone details), server.md (car details), setup.md (user guide), progress.md (history)
- **gradle.properties** — versionCode + versionName (shared by both apps)

## How to Build
```bash
./gradlew :app-client:assembleDebug
```
APK output: app-client/build/outputs/apk/debug/app-client-debug.apk
Requires JDK 17. Android SDK 34 is pre-installed on this runner.

## Before Making Changes
1. Read the relevant docs in docs/ to understand the architecture
2. Read the relevant source files to understand the current implementation
3. Use Grep/Glob to find where things are defined and referenced

## Making Changes
- Make ALL code changes needed to complete the task — do NOT wait for approval
- Prefer editing existing files over creating new ones
- Follow existing code patterns and conventions
- Do NOT push code or create PRs — the script handles that

ENDPROMPT

  # Append the issue-specific parts (variable expansion intentional here)
  cat >> /tmp/agent-prompt.txt << ENDPROMPT
## Your Task — GitHub Issue #${ISSUE_NUM}: ${ISSUE_TITLE}

${ISSUE_BODY}

## After Finishing
1. Build the APK with \`./gradlew :app-client:assembleDebug\` to verify everything compiles
2. If the build fails, fix the errors and rebuild until it passes
3. Output this EXACT JSON block as the VERY LAST thing in your response:

\`\`\`json
{"summary": "Concise description of the investigation, changes made, and outcome. Include what you verified and what still needs human testing.", "changes_made": true, "build_success": true}
\`\`\`
ENDPROMPT
}

write_resume_prompt() {
  local comment="$1"

  cat > /tmp/agent-prompt.txt << 'ENDPROMPT'
You are continuing your previous work on the DiLink-Auto project. Pick up where you left off — review the code changes on this branch and address the user's feedback.

## Before Responding
1. Read any files you modified previously to understand current state
2. Use `git diff` to see what was changed

ENDPROMPT

  cat >> /tmp/agent-prompt.txt << ENDPROMPT
## The User's Follow-Up Comment

${comment}

## After Finishing
1. If you made code changes, build the APK: \`./gradlew :app-client:assembleDebug\`
2. If the build fails, fix the errors and rebuild until it passes
3. Output this EXACT JSON block as the VERY LAST thing in your response:

\`\`\`json
{"summary": "What you did in response to the follow-up. Include build status.", "changes_made": true, "build_success": true}
\`\`\`
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

  if ! OUTPUT=$($CLAUDE_BIN --dangerously-skip-permissions -p "$(cat /tmp/agent-prompt.txt)" 2>&1); then
    handle_error "Claude Code exited with code $?" "Prompt was written to /tmp/agent-prompt.txt"
  fi

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

  post_comment "$(cat /tmp/summary-comment.md)"

elif [ "$EVENT" = "issue_comment" ]; then
  echo "--- Issue comment: resuming or starting conversation ---"

  if [ -f "$STATE_FILE" ]; then
    CONV_ID=$(jq -r '.conversation_id' "$STATE_FILE")
    echo "Found prior state — resuming conversation: $CONV_ID"

    write_resume_prompt "$COMMENT_BODY"

    if ! OUTPUT=$($CLAUDE_BIN --dangerously-skip-permissions --resume "$CONV_ID" -p "$(cat /tmp/agent-prompt.txt)" 2>&1); then
      handle_error "Claude Code exited with code $?" "Conversation ID: $CONV_ID"
    fi
  else
    echo "No prior state — treating as new for issue #$ISSUE_NUM"
    write_initial_prompt

    if ! OUTPUT=$($CLAUDE_BIN --dangerously-skip-permissions -p "$(cat /tmp/agent-prompt.txt)" 2>&1); then
      handle_error "Claude Code exited with code $?"
    fi

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

  post_comment "$(cat /tmp/summary-comment.md)"
fi

echo "=========================================="
echo " Done"
echo "=========================================="
