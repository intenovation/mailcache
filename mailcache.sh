#!/bin/bash
# mailcache-cli.sh - CLI script for MailCache integrated system

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
JAR_PATH="$SCRIPT_DIR/target/mailcache-1.0.0-jar-with-dependencies.jar"

if [ $# -eq 0 ]; then
    # Show usage if no arguments provided
    java -jar "$JAR_PATH" --help
    exit 0
fi

# Special handling for 'list-apps' command
if [ "$1" = "list-apps" ] || [ "$1" = "apps" ]; then
    java -jar "$JAR_PATH" --list-apps
    exit 0
fi

# Special handling for direct app commands
if [ "$1" = "pass" ] || [ "$1" = "password" ]; then
    shift
    java -jar "$JAR_PATH" --app "Password Manager" "$@"
    exit $?
fi

if [ "$1" = "mail" ] || [ "$1" = "mailcache" ]; then
    shift
    java -jar "$JAR_PATH" --app "MailCache CLI" "$@"
    exit $?
fi

# Default behavior - pass all arguments to the jar
java -jar "$JAR_PATH" "$@"
exit $?