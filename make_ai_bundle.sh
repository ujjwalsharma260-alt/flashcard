#!/bin/bash
# Builds AI_FULL_CONTEXT.md = the guide (AI_CONTEXT.md) + the current full source code of the app.
OUT=AI_FULL_CONTEXT.md
{
  cat AI_CONTEXT.md
  echo
  echo "---"
  echo
  echo "# 10. FULL SOURCE CODE (current, exact)"
  echo
  for f in $(find app/src/main -type f \( -name '*.kt' -o -name 'AndroidManifest.xml' -o -name 'shell.js' -o -name 'themes.xml' \) | sort) app/build.gradle.kts build.gradle.kts settings.gradle.kts gradle.properties .github/workflows/build.yml; do
    echo "## FILE: $f"
    echo
    case "$f" in
      *.kt) echo '```kotlin' ;;
      *.kts) echo '```kotlin' ;;
      *.js) echo '```javascript' ;;
      *.xml) echo '```xml' ;;
      *.yml) echo '```yaml' ;;
      *) echo '```' ;;
    esac
    cat "$f"
    echo
    echo '```'
    echo
  done
} > "$OUT"
