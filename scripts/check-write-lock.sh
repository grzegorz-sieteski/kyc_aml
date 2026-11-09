#!/usr/bin/env bash
#
# Phase boundary check for the AI SDLC workflow.
#
# The write locks described in .github/agents/*.agent.md cannot be enforced by an IDE: nothing
# stops an agent with edit access from touching src/test/ when it was told not to. This script
# is what turns those rules into something checkable. Run it after every stage.
#
#   ./scripts/check-write-lock.sh red     # test-designer: contracts compile, tests fail
#   ./scripts/check-write-lock.sh green   # developer: src/test/ and docs/ untouched
#   ./scripts/check-write-lock.sh qa      # qa-explorer: src/main/ untouched
#
# BASE may be set to compare against something other than HEAD, e.g. BASE=origin/main.

set -uo pipefail

PHASE="${1:-}"
BASE="${BASE:-HEAD}"
MVNW="./mvnw"
[ -x "$MVNW" ] || MVNW="mvn"

red()   { printf '\033[31m%s\033[0m\n' "$1"; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }

changed_under() {
    git diff --name-only "$BASE" -- "$@" 2>/dev/null
    git ls-files --others --exclude-standard -- "$@" 2>/dev/null
}

assert_untouched() {
    local label="$1"; shift
    local files
    files="$(changed_under "$@" | sort -u)"
    if [ -n "$files" ]; then
        red "FAIL: ${label} must not be modified in this phase, but these changed:"
        printf '  %s\n' $files
        return 1
    fi
    green "OK: ${label} untouched"
    return 0
}

case "$PHASE" in
    red)
        echo "== RED phase: contracts must compile, tests must fail =="
        if ! $MVNW -B -q test-compile; then
            red "FAIL: ./mvnw test-compile did not succeed. Contracts and stubs must compile."
            exit 1
        fi
        green "OK: test-compile succeeded"

        if $MVNW -B -q test > /dev/null 2>&1; then
            red "FAIL: ./mvnw test PASSED. In the RED phase that means the tests assert nothing"
            red "      the null-returning stubs do not already satisfy. Strengthen them."
            exit 1
        fi
        green "OK: tests fail as required by the RED phase"
        ;;

    green)
        echo "== GREEN phase: only src/main may change =="
        rc=0
        ALLOWLIST="scripts/green-signature-allowlist.txt"
        allowed="$( [ -f "$ALLOWLIST" ] && grep -v '^#' "$ALLOWLIST" | grep . || true )"
        if [ -z "$allowed" ]; then
            assert_untouched "src/test/" src/test || rc=1
        else
            # A signature migration is in progress: listed test files may change, but only in
            # ways that do not alter WHAT a test checks. Every other test file stays frozen.
            touched="$(changed_under src/test | sort -u)"
            for f in $touched; do
                if ! printf '%s\n' "$allowed" | grep -qxF "$f"; then
                    red "FAIL: $f is not on the signature-migration allowlist"
                    rc=1
                fi
            done
            # Changed lines in allowlisted files must not touch an assertion, a test annotation
            # or a display name. Constructor calls, imports and types may change; verdicts may not.
            guard='assert|\.is[A-Z]|\.has[A-Z]|\.contains|\.doesNot|\.satisfies|\.allSatisfy|\.extracting|\.matches|@Test|@ParameterizedTest|@DisplayName|@ValueSource|@CsvSource|@MethodSource|@EnumSource|@Nested|@Disabled'
            for f in $allowed; do
                hits="$(git diff "$BASE" -- "$f" 2>/dev/null | grep -E '^[+-][^+-]' | grep -E "$guard" || true)"
                if [ -n "$hits" ]; then
                    red "FAIL: $f changes a line that decides what the test checks:"
                    printf '%s\n' "$hits" | sed 's/^/    /' | head -20
                    rc=1
                fi
            done
            [ "$rc" -eq 0 ] && green "OK: src/test/ changes are mechanical and confined to the allowlist"
        fi
        assert_untouched "docs/" docs || rc=1
        [ "$rc" -eq 0 ] || {
            red ""
            red "Changing a test to make the build pass is always the shortest path to green,"
            red "and it produces a passing build over broken software. Revert these files and"
            red "report the problem to @test-designer instead."
            exit 1
        }
        echo "== running full verify =="
        $MVNW -B verify || { red "FAIL: ./mvnw verify did not pass"; exit 1; }
        green "OK: GREEN phase gate passed"
        ;;

    qa)
        echo "== QA phase: src/main must not be patched =="
        assert_untouched "src/main/" src/main || {
            red ""
            red "Patching the code you found the bug in means nobody learns the bug existed."
            red "Report it and add a failing test; @developer fixes it."
            exit 1
        }
        green "OK: QA phase gate passed"
        ;;

    *)
        echo "usage: $0 {red|green|qa}   (optionally with BASE=origin/main)" >&2
        exit 2
        ;;
esac
