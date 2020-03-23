#!/usr/bin/env bash

set -e

shopt -s expand_aliases

source $HOME/.bashrc

exec $STESTS_PATH_SH/workers/interactive.sh
