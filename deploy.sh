#!/bin/bash
# Builds and deploys the MCP plugin jar to Willikins, backing up the previous
# jar first (timestamped, inside the container), and restarts the server.
# Run from anywhere - paths are relative to this script's own location.
#
# Usage: deploy.sh
set -e

HOST="chad@willikins.cetacean-cloud.ts.net"
PLUGIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Building..."
(cd "$PLUGIN_DIR" && mvn -q -f pom.xml package)

# Derive the jar name from what the build actually produced rather than
# hardcoding it -- a hardcoded name silently breaks on every version bump,
# which is exactly what happened going from 0.2.0 to 0.3.0-SNAPSHOT.
JAR_PATH="$(ls -t "$PLUGIN_DIR"/target/MCP-*.jar 2>/dev/null | grep -v '/original-' | head -1)"
if [ -z "$JAR_PATH" ]; then
    echo "No MCP-*.jar found in $PLUGIN_DIR/target after build" >&2
    exit 1
fi
JAR_NAME="$(basename "$JAR_PATH")"
echo "Built $JAR_NAME"

echo "Backing up previous jar (if any)..."
ssh "$HOST" "docker exec hytale sh -c 'test -f /home/hytale/server-files/mods/$JAR_NAME && cp /home/hytale/server-files/mods/$JAR_NAME /home/hytale/server-files/mods/$JAR_NAME.bak-\$(date +%Y%m%d-%H%M%S) || true'"

echo "Copying jar to server..."
scp -q "$PLUGIN_DIR/target/$JAR_NAME" "$HOST":~/"$JAR_NAME"

echo "Installing jar and restarting..."
ssh "$HOST" "docker cp ~/$JAR_NAME hytale:/home/hytale/server-files/mods/$JAR_NAME && docker restart hytale"

echo "Waiting for server to come back up..."
sleep 14
ssh "$HOST" "docker ps | grep hytale"

echo ""
echo "Recent log lines (check for a clean init):"
ssh "$HOST" "docker exec hytale sh -c 'LOG=\$(ls -t /home/hytale/server-files/logs/*_server.log | head -1); grep -iE \"MCP\\|P\" \"\$LOG\" | tail -15'"
