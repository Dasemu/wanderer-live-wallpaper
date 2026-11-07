#!/usr/bin/env bash
# PostToolUse: format the edited Kotlin file with ktlint when it is installed.
f=$(python3 -c 'import json,sys;print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))')
case "$f" in
  *.kt|*.kts) command -v ktlint >/dev/null && ktlint -F "$f" >/dev/null 2>&1 ;;
esac
exit 0
