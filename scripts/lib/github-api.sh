#!/bin/bash
# DiLink-Auto Issue Agent — GitHub API helpers (status comments, error handling)
# Dependencies: scripts/lib/logging.sh (sourced automatically)
# Requires these globals to be set by the caller:
#   STATE_FILE, ISSUE_NUM, REPO, GITHUB_TOKEN, SERVER_URL, RUN_ID
#   GITHUB_API_IP (optional) — set by issue-agent.sh to bypass WSL DNS

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/logging.sh"

# Build --resolve flag for curl if GITHUB_API_IP is known
_resolve_flag() {
  if [ -n "${GITHUB_API_IP:-}" ]; then
    echo "--resolve api.github.com:443:${GITHUB_API_IP}"
  fi
}

# Post or update a single status comment — first call creates, later calls edit
status() {
  log_step "status() posting comment"
  _status_body="$1"
  _status_body_file="/tmp/agent-comment-body-${ISSUE_NUM}.json"
  echo "$_status_body" | jq -R -s '{body: .}' > "$_status_body_file" 2>/dev/null || {
    log_err "status: jq failed to build JSON"
    return
  }
  log_ok "body JSON written"

  _status_id=""
  [ -f "$STATE_FILE" ] && _status_id=$(jq -r '.status_comment_id // ""' "$STATE_FILE" 2>/dev/null || true)

  _resolve=$(_resolve_flag)

  _status_http_code=""
  if [ -n "$_status_id" ] && [ "$_status_id" != "null" ]; then
    _status_http_code=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH \
      -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      -H "Content-Type: application/json" \
      ${_resolve} \
      "https://api.github.com/repos/${REPO}/issues/comments/${_status_id}" \
      -d "@${_status_body_file}" 2>/dev/null || echo "000")
    if [ "$_status_http_code" != "200" ]; then
      echo "[status] WARNING: PATCH returned HTTP $_status_http_code — creating new comment"
      _status_id=""
    fi
  fi

  if [ -z "$_status_id" ] || [ "$_status_id" = "null" ]; then
    _status_resp=$(curl -s -w "\n%{http_code}" -X POST \
      -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      -H "Content-Type: application/json" \
      ${_resolve} \
      "https://api.github.com/repos/${REPO}/issues/${ISSUE_NUM}/comments" \
      -d "@${_status_body_file}" 2>/dev/null)
    _status_http_code=$(echo "$_status_resp" | tail -1)
    _new_id=$(echo "$_status_resp" | sed '$d' | jq -r '.id // ""')
    if [ "$_status_http_code" = "201" ] && [ -n "$_new_id" ] && [ "$_new_id" != "null" ]; then
      if [ -f "$STATE_FILE" ]; then
        jq --arg sid "$_new_id" '. + {status_comment_id: $sid}' "$STATE_FILE" > "${STATE_FILE}.tmp" 2>/dev/null && \
          mv "${STATE_FILE}.tmp" "$STATE_FILE" || true
      fi
      echo "[status] Comment posted (id=$_new_id)"
    else
      echo "[status] ERROR: POST returned HTTP $_status_http_code — body saved to $_status_body_file"
    fi
  else
    echo "[status] Comment updated (id=$_status_id)"
  fi
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
  status "$(cat /tmp/error-comment.txt)"
  exit 1
}
