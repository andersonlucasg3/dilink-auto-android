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
# Prevent line-ending conversion (repo is Windows/CRLF, runner is Linux/LF)
git config core.autocrlf false

# --- Helpers ---

# Add an emoji reaction to the triggering comment (or issue if no comment)
# Usage: react eyes | react rocket | react heart | react confused
react() {
  local content="$1"
  local list_target
  if [ -n "${COMMENT_ID:-}" ]; then
    list_target="repos/$REPO/issues/comments/$COMMENT_ID/reactions"
  else
    list_target="repos/$REPO/issues/$ISSUE_NUM/reactions"
  fi

  echo "[reaction] Setting :${content}: on ${list_target}"
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

# Register a headless session so --resume can find it later
register_session() {
  local cid="$1"
  local cwd="${2:-$(pwd -P)}"
  local sessions_dir="$(dirname "$CLAUDE_PROJECTS_DIR")/sessions"
  mkdir -p "$sessions_dir"
  cat > "${sessions_dir}/${cid}.json" << SESSIONEOF
{"pid":0,"sessionId":"${cid}","cwd":"${cwd}","startedAt":$(date +%s)000,"version":"2.1.120","kind":"headless","entrypoint":"claude-cli"}
SESSIONEOF
  echo "[session] Registered ${cid} (cwd: ${cwd})"
}

# --- Prompt builders ---
# Each writes to /tmp/agent-prompt-${ISSUE_NUM}.txt to avoid shell escaping issues

write_initial_prompt() {
  cat > /tmp/agent-prompt-${ISSUE_NUM}.txt << 'ENDPROMPT'
You are an autonomous development agent for **DiLink-Auto** — an open-source Android Auto alternative for BYD DiLink 3.0+ cars. Phone apps run on a virtual display, encode as H.264 video, and stream to the car over WiFi TCP. Touch events flow back from car to phone.

Read all docs in docs/*.md before starting.
Build with: `./gradlew :app-client:assembleDebug`
This is a temporary GitHub Actions runner session. You must `git add -A && git commit` all changes before your final output.

CRITICAL: This is a temporary GitHub Actions runner session — git add -A && git commit all changes before finishing. You may use gh pr (create/view/diff/review). Do NOT use gh issue comment or GitHub issue API — the script handles comments, reactions, push, and issue close.

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

Set "action" to "close" to close the issue, "pr" to create a pull request to develop, or "none".
ENDPROMPT
}

write_resume_prompt() {
  local comment="$1"

  cat >> /tmp/agent-prompt-${ISSUE_NUM}.txt << ENDPROMPT
## Follow-Up Comment

${comment}

CRITICAL: This is a temporary GitHub Actions runner session — git add -A && git commit all changes before finishing. You may use gh pr (create/view/diff/review). Do NOT use gh issue comment or GitHub issue API — the script handles comments, reactions, push, and issue close.

## After Finishing
1. Review previous changes on this branch with \`git diff HEAD~1\`
2. If you make code changes, build: \`./gradlew :app-client:assembleDebug\`
3. If the build fails, fix and rebuild
4. Output this JSON block as the VERY LAST thing.
   The "summary" field must use markdown with clear sections.

\`\`\`json
{"summary": "...", "changes_made": true, "build_success": true, "action": "none"}
\`\`\`

Set "action" to "close" to close the issue, "pr" to create a pull request to develop, or "none".
ENDPROMPT
}

# --- Main ---
echo "=========================================="
echo " DiLink-Auto Issue Agent"
echo " Event:  $EVENT"
echo " Issue:  #$ISSUE_NUM"
echo "=========================================="

BRANCH=$(branch_name)

# Set up branch — reuse if it exists (resume), create fresh if new
git fetch origin develop "$BRANCH" 2>/dev/null || true

# Discard any leftover changes and prune stale worktrees
git checkout -- . 2>/dev/null || true
git clean -ffdx -e '.gradle' 2>/dev/null || true
git worktree prune 2>/dev/null || true

# Use a dedicated git worktree per issue for isolation and consistent paths
ISSUE_WORKTREE="$AGENT_STATE_DIR/../worktrees/issue-${ISSUE_NUM}"
git fetch origin develop "$BRANCH" 2>/dev/null || true

if [ -d "$ISSUE_WORKTREE" ]; then
  echo "--- Reusing worktree: $ISSUE_WORKTREE ---"
  cd "$ISSUE_WORKTREE"
  git checkout "$BRANCH" 2>/dev/null || git checkout -b "$BRANCH" "origin/$BRANCH"
  git pull origin "$BRANCH" 2>/dev/null || true
else
  echo "--- Creating worktree: $ISSUE_WORKTREE ---"
  if git rev-parse --verify "origin/$BRANCH" >/dev/null 2>&1; then
    git worktree add "$ISSUE_WORKTREE" "origin/$BRANCH" 2>/dev/null || {
      # Worktree may already exist but be unregistered — force it
      git worktree prune 2>/dev/null || true
      mkdir -p "$ISSUE_WORKTREE"
      cd "$ISSUE_WORKTREE"
      git init
      git remote add origin "https://github.com/${REPO}.git"
      git fetch origin "$BRANCH"
      git checkout -b "$BRANCH" "origin/$BRANCH"
    }
  else
    git worktree add -b "$BRANCH" "$ISSUE_WORKTREE" origin/develop 2>/dev/null || {
      git worktree prune 2>/dev/null || true
      mkdir -p "$ISSUE_WORKTREE"
      cd "$ISSUE_WORKTREE"
      git init
      git remote add origin "https://github.com/${REPO}.git"
      git fetch origin develop
      git checkout -b "$BRANCH" origin/develop
    }
  fi
  cd "$ISSUE_WORKTREE"
fi

echo "Working in: $(pwd)"

# Record which .jsonl files exist before the run (to detect the new one)
BEFORE_JSONLS=$(find "$CLAUDE_PROJECTS_DIR" -name '*.jsonl' -type f 2>/dev/null | sort || true)

# --- Run Claude Code ---
react eyes
if [ "$EVENT" = "issues" ]; then
  echo "--- New issue: starting fresh conversation ---"
  write_initial_prompt

  echo "--- Starting Claude Code (new conversation) ---"
  set +e
  OUTPUT=$(timeout 3600 $CLAUDE_BIN --dangerously-skip-permissions -p "Start by reading /tmp/agent-prompt-${ISSUE_NUM}.txt and complete the task described there." 2>&1)
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
  register_session "$CONV_ID"

  # Commit and push if changes were made
  CHANGES_MADE=$(echo "$SUMMARY_JSON" | jq -r '.changes_made // false')
  COMMIT_SHA=""
  git add -A
  if ! git diff --quiet --cached; then
    git diff --cached --stat
    git commit -m "$(echo "$SUMMARY_JSON" | jq -r '"Agent: \(.summary)"')" || true
  fi
  # Push if local differs from remote (agent may have committed without pushing)
  if git rev-parse "origin/$BRANCH" >/dev/null 2>&1; then
    if [ "$(git rev-parse HEAD)" != "$(git rev-parse "origin/$BRANCH")" ]; then
      git push origin "$BRANCH" || echo "Warning: push failed (non-fatal)"
    fi
  else
    git push origin "$BRANCH" || echo "Warning: push failed (non-fatal)"
  fi
  COMMIT_SHA=$(git rev-parse --short HEAD)

  # Always build after the agent finishes (don't trust agent's build claim)
  echo "--- Building APK ---"
  rm -f app-client/build/outputs/apk/debug/app-client-debug.apk
  chmod +x gradlew 2>/dev/null || true
  # Convert CRLF to LF (Windows repo, Linux runner)
  sed -i 's/\r$//' gradlew 2>/dev/null || true
  # Kill any stale Gradle daemon from previous runs
  ./gradlew --stop 2>/dev/null || true
  # Ensure JDK 17 from setup-java, not Windows JDK 25 from PATH
  export JAVA_HOME="${JAVA_HOME_17_X64:-$JAVA_HOME}"
  export PATH="${JAVA_HOME}/bin:$PATH"
  # Ensure Android SDK is available
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
✅ APK built — [download](${SERVER_URL}/${REPO}/releases/tag/issue-${ISSUE_NUM}-debug)

EOFCOMMENT
  else
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

  # Handle agent-requested actions
  ACTION=$(echo "$SUMMARY_JSON" | jq -r '.action // "none"')
  if [ "$ACTION" = "close" ]; then
    echo "[action] Closing issue #$ISSUE_NUM per agent request"
    GH_TOKEN="$GITHUB_TOKEN" gh issue close "$ISSUE_NUM" 2>/dev/null || true
    # Cleanup worktree and state
    git worktree remove "$ISSUE_WORKTREE" --force 2>/dev/null || true
    rm -f "$STATE_FILE" 2>/dev/null || true
  elif [ "$ACTION" = "pr" ]; then
    echo "[action] Creating pull request for $BRANCH → develop"
    PR_URL=$(GH_TOKEN="$GITHUB_TOKEN" gh pr create \
      --base develop \
      --head "$BRANCH" \
      --title "${ISSUE_TITLE}" \
      --body "Closes #${ISSUE_NUM}" \
      2>/dev/null || true)
    if [ -n "$PR_URL" ]; then
      echo "PR created: $PR_URL"
    fi
  fi

elif [ "$EVENT" = "issue_comment" ]; then
  echo "--- Issue comment: resuming or starting conversation ---"

  if [ -f "$STATE_FILE" ]; then
    CONV_ID=$(jq -r '.conversation_id' "$STATE_FILE")
    echo "Found prior state — resuming conversation: $CONV_ID"

    write_resume_prompt "$COMMENT_BODY"

    echo "--- Resuming Claude Code conversation: $CONV_ID (5m timeout) ---"

    # Copy conversation to all runner project dirs so --resume finds it
    # (Claude resolves git root for project hash; we can't predict which runner)
    OLD_CONV=$(find "$CLAUDE_PROJECTS_DIR" -name "${CONV_ID}.jsonl" -type f 2>/dev/null | head -1)
    if [ -n "$OLD_CONV" ]; then
      OLD_PROJ=$(dirname "$OLD_CONV")
      for proj in $(find "$CLAUDE_PROJECTS_DIR" -maxdepth 1 -type d -name '--*' 2>/dev/null); do
        if [ "$proj" != "$OLD_PROJ" ]; then
          cp -f "$OLD_CONV" "$proj/${CONV_ID}.jsonl" 2>/dev/null || true
          [ -d "${OLD_PROJ}/${CONV_ID}" ] && cp -rf "${OLD_PROJ}/${CONV_ID}" "$proj/${CONV_ID}" 2>/dev/null || true
        fi
      done
      echo "[resume] Copied conversation to all runner projects"
    fi

    register_session "$CONV_ID"

    set +e
    OUTPUT=$(timeout 300 $CLAUDE_BIN --dangerously-skip-permissions --resume "$CONV_ID" -p "Start by reading /tmp/agent-prompt-${ISSUE_NUM}.txt and complete the task described there." 2>&1)
    CLAUDE_EXIT=$?
    set -e
    if [ "$CLAUDE_EXIT" -ne 0 ]; then
      echo "--- Resume failed (exit $CLAUDE_EXIT), starting fresh ---"
      rm -f "$STATE_FILE"
      write_initial_prompt
      set +e
      OUTPUT=$(timeout 3600 $CLAUDE_BIN --dangerously-skip-permissions -p "Start by reading /tmp/agent-prompt-${ISSUE_NUM}.txt and complete the task described there." 2>&1)
      CLAUDE_EXIT=$?
      set -e
      if [ "$CLAUDE_EXIT" -ne 0 ]; then
        handle_error "Claude Code exited with code $CLAUDE_EXIT"
      fi
      CONV_ID=$(capture_conversation_id "$OUTPUT")
      jq -n \
        --arg cid "$CONV_ID" \
        --arg branch "$BRANCH" \
        --arg issue "$ISSUE_NUM" \
        --arg title "$ISSUE_TITLE" \
        '{conversation_id: $cid, branch: $branch, issue_number: $issue, title: $title}' \
        > "$STATE_FILE"
      register_session "$CONV_ID"
    fi
    echo "--- Claude Code finished ---"
  else
    echo "No prior state — treating as new for issue #$ISSUE_NUM"
    write_initial_prompt

    echo "--- Starting Claude Code (new conversation, no prior state) ---"

    STABLE_WORK="/tmp/issue-agent-${ISSUE_NUM}"
    rm -rf "$STABLE_WORK"
    ln -sf "$(pwd -P)" "$STABLE_WORK"

    set +e
    OUTPUT=$(cd "$STABLE_WORK" && timeout 3600 $CLAUDE_BIN --dangerously-skip-permissions -p "Start by reading /tmp/agent-prompt-${ISSUE_NUM}.txt and complete the task described there." 2>&1)
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
      register_session "$CONV_ID"
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
      git commit -m "$(echo "$SUMMARY_JSON" | jq -r '"Agent follow-up: \(.summary)"')" || true
      git push origin "$BRANCH" || echo "Warning: push failed (non-fatal)"
      COMMIT_SHA=$(git rev-parse --short HEAD)
    fi
  fi

  # Always build after the agent finishes (don't trust agent's build claim)
  echo "--- Building APK ---"
  rm -f app-client/build/outputs/apk/debug/app-client-debug.apk
  chmod +x gradlew 2>/dev/null || true
  # Convert CRLF to LF (Windows repo, Linux runner)
  sed -i 's/\r$//' gradlew 2>/dev/null || true
  # Kill any stale Gradle daemon from previous runs
  ./gradlew --stop 2>/dev/null || true
  # Ensure JDK 17 from setup-java, not Windows JDK 25 from PATH
  export JAVA_HOME="${JAVA_HOME_17_X64:-$JAVA_HOME}"
  export PATH="${JAVA_HOME}/bin:$PATH"
  # Ensure Android SDK is available
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
✅ APK built — [download](${SERVER_URL}/${REPO}/releases/tag/issue-${ISSUE_NUM}-debug)

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

  post_comment "$(cat /tmp/summary-comment.md)"

  # Handle agent-requested actions
  ACTION=$(echo "$SUMMARY_JSON" | jq -r '.action // "none"')
  if [ "$ACTION" = "close" ]; then
    echo "[action] Closing issue #$ISSUE_NUM per agent request"
    GH_TOKEN="$GITHUB_TOKEN" gh issue close "$ISSUE_NUM" 2>/dev/null || true
    # Cleanup worktree and state
    git worktree remove "$ISSUE_WORKTREE" --force 2>/dev/null || true
    rm -f "$STATE_FILE" 2>/dev/null || true
  elif [ "$ACTION" = "pr" ]; then
    echo "[action] Creating pull request for $BRANCH → develop"
    PR_URL=$(GH_TOKEN="$GITHUB_TOKEN" gh pr create \
      --base develop \
      --head "$BRANCH" \
      --title "${ISSUE_TITLE}" \
      --body "Closes #${ISSUE_NUM}" \
      2>/dev/null || true)
    if [ -n "$PR_URL" ]; then
      echo "PR created: $PR_URL"
    fi
  fi
fi

# Stop Gradle daemon to free resources for the next job
./gradlew --stop 2>/dev/null || true
echo "=========================================="
echo " Done"
echo "=========================================="
