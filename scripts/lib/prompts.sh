#!/bin/bash
# DiLink-Auto Issue Agent — Claude Code prompt builders
# Requires these globals to be set by the caller:
#   ISSUE_NUM, ISSUE_TITLE, ISSUE_BODY, GITHUB_TOKEN, REPO, STATUS_COMMENT_ID,
#   CLAUDE_PROJECTS_DIR

# Find the conversation ID from Claude Code's output or filesystem
capture_conversation_id() {
  local output="$1"
  local cid

  # Try parsing from stdout
  cid=$(echo "$output" | grep -oiP 'conversation[_-]?\s*(id)?:\s*\K[a-f0-9-]{20,}' | head -1 || true)

  # Fallback: newest .jsonl conversation across ALL project dirs
  if [ -z "$cid" ] && [ -d "$CLAUDE_PROJECTS_DIR" ]; then
    cid=$(find "$CLAUDE_PROJECTS_DIR" -name '*.jsonl' -type f 2>/dev/null \
      | grep -v '/subagents/' \
      | xargs ls -t 2>/dev/null | head -1 | xargs basename | sed 's/\.jsonl$//' || true)
  fi
  # Reject invalid IDs (e.g. "." from corrupted detection)
  if [ "$cid" = "." ] || [ "${#cid}" -lt 20 ]; then
    cid=""
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

# Register a headless session so --resume can find it later
register_session() {
  local cid="$1"
  local cwd="${2:-$(pwd -P)}"
  local sessions_dir="$(dirname "$CLAUDE_PROJECTS_DIR")/sessions"
  mkdir -p "$sessions_dir"
  cat > "${sessions_dir}/${cid}.json" << SESSIONEOF
{"pid":0,"sessionId":"${cid}","cwd":"${cwd}","startedAt":$(date +%s)000,"version":"2.1.121","kind":"headless","entrypoint":"claude-cli"}
SESSIONEOF
  echo "[session] Registered ${cid} (cwd: ${cwd})"
}

# --- Prompt builders ---
# Each writes to /tmp/agent-prompt-${ISSUE_NUM}.txt to avoid shell escaping issues

write_initial_prompt() {
  # Build the status update snippet
  _sid="${STATUS_COMMENT_ID:-unknown}"
  _token="${GITHUB_TOKEN:-}"
  _repo="${REPO}"
  _resolve=""
  [ -n "${GITHUB_API_IP:-}" ] && _resolve="--resolve api.github.com:443:${GITHUB_API_IP}"

  cat > /tmp/agent-prompt-${ISSUE_NUM}.txt << ENDPROMPT
You are an autonomous development agent for **DiLink-Auto** — an open-source Android Auto alternative for BYD DiLink 3.0+ cars. Phone apps run on a virtual display, encode as H.264 video, and stream to the car over WiFi TCP. Touch events flow back from car to phone.

Read all docs in docs/*.md before starting.
Build with: `./gradlew :app-client:assembleDebug`
You are already on the correct branch for this issue — do NOT create a new branch.

## ⚠️ MANDATORY: Update the Status Comment
The user sees only the GitHub issue comment — there is no other output. You MUST update it at every milestone so the user knows you are working. Use this command (replace MESSAGE with a brief status):

\`\`\`bash
curl -s -X PATCH -H "Authorization: Bearer ${_token}" \\
  -H "Accept: application/vnd.github+json" \\
  ${_resolve} \\
  "https://api.github.com/repos/${_repo}/issues/comments/${_sid}" \\
  -d "\$(jq -n --arg body "MESSAGE" '{body: \$body}')" > /dev/null
\`\`\`

Required status updates — run the command BEFORE each action (not after):
1. 📖 before reading docs → "📖 Reading documentation..."
2. 🔍 before investigating code → "🔍 Investigating the codebase..."
3. ✏️ before modifying code → "✏️ Implementing: <what you are about to change>"
4. 🔨 before building → "🔨 Building APK..."
5. ✅ after build passes → "✅ Build passed, pushing changes..."
6. ❌ if build fails → "❌ Build failed, fixing..."

CRITICAL: This is a temporary GitHub Actions runner session. Before finishing you MUST: (1) git add -A && git commit (2) git push origin HEAD. Do NOT use gh issue comment or GitHub issue API — the script handles comments and issue close.

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
  _sid="${STATUS_COMMENT_ID:-unknown}"
  _token="${GITHUB_TOKEN:-}"
  _repo="${REPO}"
  _resolve=""
  [ -n "${GITHUB_API_IP:-}" ] && _resolve="--resolve api.github.com:443:${GITHUB_API_IP}"

  cat >> /tmp/agent-prompt-${ISSUE_NUM}.txt << ENDPROMPT
## User's New Request

${comment}

## ⚠️ MANDATORY: Update the Status Comment
You MUST keep the user informed by updating the status comment at every milestone:

\`\`\`bash
curl -s -X PATCH -H "Authorization: Bearer ${_token}" \\
  -H "Accept: application/vnd.github+json" \\
  ${_resolve} \\
  "https://api.github.com/repos/${_repo}/issues/comments/${_sid}" \\
  -d "\$(jq -n --arg body "MESSAGE" '{body: \$body}')" > /dev/null
\`\`\`

Required: 📖 reading → 🔍 investigating → ✏️ implementing → 🔨 building → ✅/❌ result

## Critical Instructions
- Focus ONLY on the user's new request above. Do NOT repeat or re-implement previous work.
- If asked for ideas/analysis, provide that — don't just describe what exists.
- If asked to change direction, change it.
- Review \`git diff HEAD~1\` to see what's already on this branch.

CRITICAL: Before finishing you MUST: (1) git add -A && git commit (2) git push origin HEAD.
Do NOT use gh issue comment or GitHub issue API — the script handles comments and issue close.
ENDPROMPT
}
