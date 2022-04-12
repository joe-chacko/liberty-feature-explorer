#!/bin/sh

BASE_DIR="$(dirname $(realpath "$0"))"
SHELL_SCRIPT="$BASE_DIR/build/install/lfe/bin/lfe"


# Use a subshell in the base dir just in case the project needs to be built
(
    cd "$BASE_DIR"
    # If the script exists and is newer than the source, this find command will produce no output
    # otherwise use gradle to build the script
    [ -z "$(find src -type f -newer "$SHELL_SCRIPT" 2>&1)" ] || ./gradlew clean install
)

"$SHELL_SCRIPT" "$@"
