#!/bin/zsh
set -eu
cd "${0:A:h}"
for siege_jdk in "${JAVA_HOME:-/nonexistent}" \
  "$HOME/.local/share/mise/installs/java/26.0.0" \
  /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home; do
  if [[ -x "$siege_jdk/bin/java" ]]; then
    siege_version=$("$siege_jdk/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')
    if [[ -n "$siege_version" && "$siege_version" -ge 25 ]]; then
      export JAVA_HOME="$siege_jdk"
      if [[ ! -f 'run-challenge/saves/Sling Siege Challenge/level.dat' ]]; then
        print 'The prepared world is missing. Create a Creative world with commands enabled, then type /sling level.'
        exec ./gradlew runClient --console=plain
      fi
      python3 tools/backup_before_update.py
      print 'Worlds Garden: 9 + right-click opens the game grid; 8 opens the guide. In a game, 9 returns to the garden. Siege: 1-3 birds, 4-8 tools. Bloom: click fire, R reload, G interact.'
      exec ./gradlew runClient -PsiegePlay=true --console=plain
    fi
  fi
done
print 'Sling Siege needs an installed JDK 25 or newer. Set JAVA_HOME to that JDK and try again.'
exit 1
