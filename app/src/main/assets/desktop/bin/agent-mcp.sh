#!/bin/bash
# agent-mcp: gives your coding agents their MCP servers back (after a fresh install, or any time).
#
#   agent-mcp              Claude Code and OpenCode
#   agent-mcp claude       just one of them (claude | opencode)
#   agent-mcp list         what the list holds, and what each agent has now
#
# The list is /usr/local/share/omalauncher/mcp-servers.json, shipped with the launcher. A server is only added
# when the agent has none by that name: your own changes to a server are never overwritten. The launcher runs
# this after it installs Claude Code or OpenCode.
LIST=/usr/local/share/omalauncher/mcp-servers.json
OC=$HOME/.config/opencode/opencode.json
export PATH="$PATH:$HOME/.local/share/mise/shims:$HOME/.local/bin"
command -v jq >/dev/null || { echo "agent-mcp: needs jq: pacman -S jq" >&2; exit 1; }
[ -f "$LIST" ] || { echo "agent-mcp: $LIST is missing" >&2; exit 1; }

# What a server needs on this system, said once so a missing one is easy to fix.
needs() {
  local name=$1 json=$2 cmd
  cmd=$(jq -r 'if (.command|type)=="array" then .command[0] else .command end' <<<"$json")
  command -v "$cmd" >/dev/null || [ -x "$cmd" ] || echo "    note: $name runs '$cmd', not installed yet (npx: mise use -g node@lts; uvx: mise use -g uv)"
  grep -q /usr/bin/chromium <<<"$json" && [ ! -x /usr/bin/chromium ] && echo "    note: $name needs Chromium: pacman -S chromium"
}

claude_sync() {
  command -v claude >/dev/null || { echo "Claude Code: not installed, skipped"; return; }
  local have name json
  have=$(jq -r '.mcpServers // {} | keys[]' "$HOME/.claude.json" 2>/dev/null)
  echo "Claude Code:"
  for name in $(jq -r '.claude | keys[]' "$LIST"); do
    json=$(jq -c --arg n "$name" '.claude[$n]' "$LIST")
    if grep -qx "$name" <<<"$have"; then echo "  $name: already there"
    elif claude mcp add-json -s user "$name" "$json" >/dev/null 2>&1; then echo "  $name: added"
    else echo "  $name: couldn't add (claude mcp add-json failed)"; fi
    needs "$name" "$json"
  done
}

opencode_sync() {
  command -v opencode >/dev/null || [ -f "$OC" ] || { echo "OpenCode: not installed, skipped"; return; }
  mkdir -p "$(dirname "$OC")"
  [ -s "$OC" ] || echo '{"$schema": "https://opencode.ai/config.json"}' >"$OC"
  local tmp; tmp=$(mktemp)
  if ! jq empty "$OC" 2>/dev/null; then echo "OpenCode: $OC isn't plain JSON (comments?), left alone"; return; fi
  echo "OpenCode:"
  # Servers put under mcp.servers by mistake aren't read by OpenCode (it says "No MCP servers configured"):
  # moved up to mcp.<name>, where it looks.
  if jq -e '.mcp.servers | type == "object" and (to_entries | all(.value | type == "object" and has("command")))' "$OC" >/dev/null 2>&1; then
    cp "$OC" "$OC.before-agent-mcp"
    jq '.mcp = ((.mcp | del(.servers)) + .mcp.servers)' "$OC" >"$tmp" && cat "$tmp" >"$OC"
    echo "  moved its servers out of mcp.servers, where OpenCode didn't see them (old file: $OC.before-agent-mcp)"
  fi
  for name in $(jq -r '.opencode | keys[]' "$LIST"); do
    if jq -e --arg n "$name" '.mcp[$n] != null' "$OC" >/dev/null 2>&1; then echo "  $name: already there"
    else
      jq --arg n "$name" --slurpfile l "$LIST" '.mcp[$n] = $l[0].opencode[$n]' "$OC" >"$tmp" && cat "$tmp" >"$OC" && echo "  $name: added"
    fi
    needs "$name" "$(jq -c --arg n "$name" '.opencode[$n]' "$LIST")"
  done
  rm -f "$tmp"
}

case "${1:-all}" in
  claude) claude_sync ;;
  opencode) opencode_sync ;;
  all) claude_sync; opencode_sync ;;
  list)
    echo "In the list:"; jq -r '"  Claude Code: " + (.claude|keys|join(", ")), "  OpenCode: " + (.opencode|keys|join(", "))' "$LIST"
    echo "Now:"; echo "  Claude Code: $(jq -r '.mcpServers // {} | keys | join(", ")' "$HOME/.claude.json" 2>/dev/null)"
    echo "  OpenCode: $(jq -r '.mcp // {} | keys | join(", ")' "$OC" 2>/dev/null)" ;;
  *) sed -n 2,9p "$0"; exit 2 ;;
esac
