#!/bin/sh
#
# Gradle start up script for POSIX — Gradle 8.6 wrapper
#
APP_NAME="Gradle"
APP_BASE_NAME=${0##*/}
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
MAX_FD=maximum

warn () { echo "$*"; } >&2
die  () { echo; echo "$*"; echo; exit 1; } >&2

cygwin=false; msys=false; darwin=false; nonstop=false
case "$( uname )" in
  CYGWIN* )        cygwin=true  ;;
  Darwin* )        darwin=true  ;;
  MSYS* | MINGW* ) msys=true    ;;
  NONSTOP* )       nonstop=true ;;
esac

# Resolve APP_HOME
app_path=$0
while [ -h "$app_path" ]; do
  ls=$( ls -ld "$app_path" )
  link=${ls#*' -> '}
  case $link in
    /*) app_path=$link ;;
    *)  app_path=$( dirname "$app_path" )/$link ;;
  esac
done
APP_HOME=$( cd "$( dirname "$app_path" )" && pwd -P )

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Find JAVA_HOME / java command
if [ -n "$JAVA_HOME" ]; then
  if [ -x "$JAVA_HOME/jre/sh/java" ]; then
    JAVACMD=$JAVA_HOME/jre/sh/java
  else
    JAVACMD=$JAVA_HOME/bin/java
  fi
  [ ! -x "$JAVACMD" ] && die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
else
  JAVACMD=java
  which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME not set and no 'java' found in PATH."
fi

# Raise max file descriptors
if ! "$cygwin" && ! "$darwin" && ! "$nonstop"; then
  case $MAX_FD in max*) MAX_FD=$( ulimit -H -n ) || warn "Could not query max fd";; esac
  case $MAX_FD in '' | soft) :;; *) ulimit -n "$MAX_FD" || warn "Could not set max fd to $MAX_FD";; esac
fi

set -- \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "$@"
