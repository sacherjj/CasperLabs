#!/bin/bash

YELLOW='\e[93m'
NC='\e[0m'

command -v remark >/dev/null 2>&1 || { echo >&2 "Package 'remark' not installed.  Aborting."; exit 1; }

LINT_OUTPUT=`remark -u validate-links -u lint-no-dead-urls . 2>&1`

LINT_OUTPUT_MODIFIED=`printf '%s\n' "${LINT_OUTPUT[@]}" | grep -v 'localhost\|warnings'`

if echo $LINT_OUTPUT_MODIFIED | grep -i "warning" 2>&1 > /dev/null; then
    printf '%s\n' "${LINT_OUTPUT[@]}"
    echo ""
    echo -e "${YELLOW}Please Fix the above broken links! Please IGNORE any localhost warnings.${NC}"
    exit 1
else
    echo "No Issues Found!!"
fi
