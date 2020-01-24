#!/usr/bin/env bash

set -eu -o pipefail

CRATES_URL=https://crates.io/api/v1/crates
GH_URL=https://api.github.com/repos/CasperLabs/CasperLabs
EE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# These are the subdirs of CasperLabs/execution-engine which contain packages for publishing.  They
# should remain ordered from least-dependent to most.
PACKAGE_DIRS=( types contract engine-wasm-prep engine-shared engine-storage engine-core engine-grpc-server engine-test-support cargo-casperlabs )

run_curl() {
    set +e
    CURL_OUTPUT=$(curl -s $1)
    set -e
    local EXIT_CODE=$?
    if [[ $EXIT_CODE -ne 0 ]]; then
        printf "curl -s %s failed with exit code %d\n\n" $1 $EXIT_CODE
        exit 1
    fi
}

check_local_repo_is_up_to_date() {
    run_curl $GH_URL/branches/dev
    LATEST_GH_COMMIT=$(echo "$CURL_OUTPUT" | python3 -c "import sys, json; print(json.load(sys.stdin)['commit']['sha'])")
    LOCAL_HEAD=$(git rev-parse HEAD)
    if [[ $LATEST_GH_COMMIT != $LOCAL_HEAD ]]; then
        printf "Local HEAD doesn't match the latest commit in 'dev'.  Aborting.\n\n"
        exit 2
    fi
}

check_python_has_toml() {
    set +e
    python3 -c "import toml" 2>/dev/null
    set -e
    if [[ $? -ne 0 ]]; then
        printf "Ensure you have 'toml' installed for Python3\n"
        printf "e.g. run\n"
        printf "    pip install toml --user\n\n"
        exit 3
    fi
}

version_in_dev() {
    local DIR_IN_EE="$1"
    printf "Version of crate '%s' from dev branch: " "$DIR_IN_EE"
    DEV_VERSION=$(cat "$EE_DIR/$DIR_IN_EE/Cargo.toml" | python3 -c "import sys, toml; print(toml.load(sys.stdin)['package']['version'])")
    printf "%s\n" $DEV_VERSION
}

max_version_in_crates_io() {
    local CRATE=$1
    printf "Max version of published crate '%s': " $CRATE
    run_curl $CRATES_URL/$CRATE
    if [[ "$CURL_OUTPUT" == "{\"errors\":[{\"detail\":\"Not Found\"}]}" ]]; then
        CRATES_IO_VERSION="N/A (not found in crates.io)"
    else
        CRATES_IO_VERSION=$(echo "$CURL_OUTPUT" | python3 -c "import sys, json; print(json.load(sys.stdin)['crate']['max_version'])")
    fi
    printf "%s\n" "$CRATES_IO_VERSION"
}

publish() {
    local DIR_IN_EE="$1"
    version_in_dev "$DIR_IN_EE"

    local CRATE_NAME=$(cat $EE_DIR/$DIR_IN_EE/Cargo.toml | python3 -c "import sys, toml; print(toml.load(sys.stdin)['package']['name'])")
    max_version_in_crates_io $CRATE_NAME

    if [[ "$DEV_VERSION" == "$CRATES_IO_VERSION" ]]; then
        printf "Skipping '%s'\n" $CRATE_NAME
    else
        printf "Publishing '%s'\n" $CRATE_NAME
        cd $EE_DIR/$DIR_IN_EE
        cargo publish
        printf "Published '%s' at version %s\n" $CRATE_NAME $DEV_VERSION
    fi
}

check_local_repo_is_up_to_date
check_python_has_toml

for PACKAGE_DIR in "${PACKAGE_DIRS[@]}"; do
    publish $PACKAGE_DIR
    printf "================================================================================\n\n"
done
