#!/bin/bash
# DiLink-Auto Issue Agent — Branch naming logic
# Requires these globals to be set by the caller:
#   ISSUE_LABELS, ISSUE_BODY, ISSUE_NUM, ISSUE_TITLE
# Sets these globals:
#   RELEASE_VERSION, RELEASE_TARGET

RELEASE_VERSION=""
RELEASE_TARGET="develop"

branch_name() {
  # Release issues have a 'release' label plus ### Version field
  if echo "${ISSUE_LABELS:-}" | jq -e 'index("release")' >/dev/null 2>&1; then
    RELEASE_VERSION=$(echo "$ISSUE_BODY" | awk '/### Version/{v=1; next} v && /^[0-9]+\.[0-9]+\.[0-9]+/{print $1; exit} /^[[:space:]]*$/{next} {v=0}' | head -1 || true)
    if [ -n "$RELEASE_VERSION" ]; then
      RELEASE_TARGET="main"
      echo "release/v${RELEASE_VERSION}"
      return
    fi
  fi

  # Map label to branch prefix
  RELEASE_TARGET="develop"
  if echo "${ISSUE_LABELS:-}" | jq -e 'index("bug")' >/dev/null 2>&1; then
    echo "fix/${ISSUE_NUM}-agent"
  elif echo "${ISSUE_LABELS:-}" | jq -e 'index("feature")' >/dev/null 2>&1; then
    echo "feature/${ISSUE_NUM}-agent"
  elif echo "${ISSUE_LABELS:-}" | jq -e 'index("investigation")' >/dev/null 2>&1; then
    echo "investigate/${ISSUE_NUM}-agent"
  elif echo "${ISSUE_LABELS:-}" | jq -e 'index("documentation")' >/dev/null 2>&1; then
    echo "docs/${ISSUE_NUM}-agent"
  else
    # --- FALLBACK: detect issue type from body when no label matched ---

    # Release template: body contains ### Version field
    RELEASE_VERSION=$(echo "$ISSUE_BODY" | awk '/### Version/{v=1; next} v && /^[0-9]+\.[0-9]+\.[0-9]+/{print $1; exit} /^[[:space:]]*$/{next} {v=0}' | head -1 || true)
    if [ -n "$RELEASE_VERSION" ]; then
      RELEASE_TARGET="main"
      echo "release/v${RELEASE_VERSION}"
      return
    fi

    # Agent Task template: body contains ### Task Type dropdown
    TASK_TYPE=$(echo "$ISSUE_BODY" | awk '/### Task Type/{v=1; next} v && /^- /{gsub(/^- /,""); print; exit} /^[[:space:]]*$/{next} {v=0}' | head -1 || true)
    case "$TASK_TYPE" in
      "Bug fix")       echo "fix/${ISSUE_NUM}-agent"; return ;;
      "New feature")   echo "feature/${ISSUE_NUM}-agent"; return ;;
      "Investigation") echo "investigate/${ISSUE_NUM}-agent"; return ;;
      "Documentation") echo "docs/${ISSUE_NUM}-agent"; return ;;
    esac

    # Title-based fallback: "Release ..." without ### Version field
    # RELEASE_TARGET stays "develop" — can't target main without a version
    if echo "${ISSUE_TITLE:-}" | grep -qi '^release'; then
      echo "release/${ISSUE_NUM}-agent"
      return
    fi

    # True catch-all
    echo "issue/${ISSUE_NUM}-agent"
  fi
}
