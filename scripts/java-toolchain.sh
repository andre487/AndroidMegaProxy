#!/usr/bin/env bash
# Source this file to select JDK 21 without installation-specific paths.
megaproxy_java_toolchain() {
    local candidate="${JAVA_HOME:-}" properties version
    if [[ -z "$candidate" && -x /usr/libexec/java_home ]]; then
        candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    fi
    if [[ -n "$candidate" ]]; then
        properties="$("$candidate/bin/java" -XshowSettings:properties -version 2>&1)" || {
            echo "JAVA_HOME must point to a working JDK 21." >&2
            return 1
        }
    else
        properties="$(java -XshowSettings:properties -version 2>&1)" || {
            echo "JDK 21 is required; configure JAVA_HOME or add it to PATH." >&2
            return 1
        }
        candidate="$(sed -n 's/^[[:space:]]*java.home = //p' <<< "$properties")"
    fi
    version="$(sed -n 's/^[[:space:]]*java.specification.version = //p' <<< "$properties")"
    if [[ "$version" != 21 || ! -x "$candidate/bin/javac" ]]; then
        echo "JDK 21 is required; configure JAVA_HOME or add it to PATH." >&2
        return 1
    fi
    export JAVA_HOME="$candidate"
    export PATH="$JAVA_HOME/bin:$PATH"
}
megaproxy_java_toolchain
