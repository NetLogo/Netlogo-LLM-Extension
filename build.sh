#!/bin/bash
# Build script for LLM Extension
# Creates fat JAR and cleans up loose dependency JARs

set -e

# NetLogo 7.0.4 is compiled for Java 21, so the build needs JDK 21 or newer
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21+)}

echo "Building LLM Extension..."
sbt clean assembly

# Clean up loose JARs created by NetLogo plugin
rm -f *.jar 2>/dev/null || true

# Update dist folder
mkdir -p dist/llm
cp target/scala-3.7.0/llm.jar dist/llm/

# Auto-install to NetLogo if NETLOGO_DIR is set
# Usage: NETLOGO_DIR="/Applications/NetLogo 7.0.4" ./build.sh
if [ -n "$NETLOGO_DIR" ]; then
  NETLOGO_EXT="$NETLOGO_DIR/extensions/llm"
  mkdir -p "$NETLOGO_EXT"
  cp target/scala-3.7.0/llm.jar "$NETLOGO_EXT/"
  echo "Installed to: $NETLOGO_EXT"
else
  echo ""
  echo "To auto-install, re-run with your NetLogo path:"
  echo "  NETLOGO_DIR=\"/Applications/NetLogo 7.0.4\" ./build.sh"
  echo ""
  echo "Or manually copy:"
  echo "  mkdir -p /path/to/NetLogo/extensions/llm"
  echo "  cp target/scala-3.7.0/llm.jar /path/to/NetLogo/extensions/llm/"
fi

echo ""
echo "Build complete!"
echo "Fat JAR: target/scala-3.7.0/llm.jar"
echo "Install folder: dist/llm/"
