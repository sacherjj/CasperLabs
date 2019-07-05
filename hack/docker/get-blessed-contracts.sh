#!/usr/bin/env bash
set -euo pipefail

# 1. Try to download blessed contracts using curl
# 2. If failed then build it locally

if [[ "$1" == /* ]] || [[ "$1" == ~* ]]; then
    OUTPUT="$1"
else
    OUTPUT="$PWD/$1"
fi

CURRENT_DIR="$PWD"

# Borrowed from https://github.com/robbyrussell/oh-my-zsh/blob/master/lib/git.zsh
git_current_branch () {
    local ref
    ref="$(command git symbolic-ref --quiet HEAD 2> /dev/null)"
    local ret="$?"
    if [[ "$ret" != 0 ]]; then
        [[ "$ret" == 128 ]] && return
        ref="$(command git rev-parse --short HEAD 2> /dev/null)"  || return
    fi
    echo "${ref#refs/heads/}"
}


GIT_CURRENT_BRANCH="$(git_current_branch)"
LINK="http://repo.casperlabs.io/casperlabs/repo/${GIT_CURRENT_BRANCH}/blessed-contracts.tar.gz"
echo "Trying to download blessed contracts from $LINK using curl..."
HTTP_STATUS_CODE="$(curl "$LINK" -o "$OUTPUT" -s -w "%{http_code}")"
if [[ "$HTTP_STATUS_CODE" != "200" ]]; then
    echo "Failed to download blessed contracts, building locally..."
    cd "../.." && make package-blessed-contracts
    cd "$CURRENT_DIR"
    cp "../../execution-engine/target/blessed-contracts.tar.gz" "$OUTPUT"
fi

$(cd "$(dirname "$OUTPUT")" && tar -xzf "$OUTPUT")
rm "$OUTPUT"