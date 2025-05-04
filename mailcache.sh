#!/bin/bash

# MailCache Integrated CLI Wrapper Script
# This script launches the integrated MailCache + PasswordManager application

# Configuration
APP_NAME="MailCache Integrated"
JAR_DIR="target"
JAR_NAME="mailcache-1.0.0-jar-with-dependencies.jar"
JAR_PATH="$JAR_DIR/$JAR_NAME"
MAIN_CLASS="com.intenovation.mailcache.MailCacheIntegrated"
REBUILD_FLAG="--rebuild"

# Function to build the application
build_app() {
    echo "Building $APP_NAME..."

    # Check if Maven is installed
    if ! command -v mvn &> /dev/null; then
        echo "Error: Maven is not installed. Please install Maven first."
        exit 1
    fi

    # Build all projects in order
    echo "Building AppFw..."
    (cd ../appfw && mvn clean install)

    echo "Building PasswordManager..."
    (cd ../passwordmanager && mvn clean install)

    echo "Building MailCache..."
    mvn clean package

    # Check if build was successful
    if [ $? -ne 0 ]; then
        echo "Error: Build failed"
        exit 1
    fi

    echo "Build successful"
}

# Function to show help
show_help() {
    echo "MailCache Integrated CLI"
    echo ""
    echo "Usage: $0 [options] [app-args]"
    echo ""
    echo "Options:"
    echo "  --rebuild          Rebuild all projects before running"
    echo "  --help             Show this help message"
    echo ""
    echo "Application arguments:"
    echo "  --app <name>       Specify application to run (PasswordManager or MailCache CLI)"
    echo "  --list             List available applications"
    echo "  --list-all-tasks   List all available tasks"
    echo ""
    echo "Examples:"
    echo "  $0 --app \"Password Manager\" add imap server.com user"
    echo "  $0 --app \"MailCache CLI\" --task sync-folder"
    echo "  $0 --list"
    echo ""
    echo "For more help on specific applications:"
    echo "  $0 --app \"Password Manager\" --help"
    echo "  $0 --app \"MailCache CLI\" --help"
}

# Check for help flag
if [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    show_help
    exit 0
fi

# Check for rebuild flag
if [ "$1" = "$REBUILD_FLAG" ]; then
    build_app
    # Remove the rebuild flag from arguments
    shift
fi

# Check if the JAR file exists
if [ ! -f "$JAR_PATH" ]; then
    echo "Warning: Could not find $JAR_PATH"
    echo "Attempting to build the application..."
    build_app
fi

# If the JAR still doesn't exist after build attempt, exit
if [ ! -f "$JAR_PATH" ]; then
    echo "Error: Could not find $JAR_PATH even after build attempt."
    echo "Please check for build errors."
    exit 1
fi

# Run the integrated application with all arguments
java -cp "$JAR_PATH" "$MAIN_CLASS" "$@"