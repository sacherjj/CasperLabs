#!/usr/bin/env bash

# Print Highway ChainSpec environment variables.

set -e

# Milliseconds since epoch.
# TIMESTAMP=$(date +%s%3N) # Doesn't work on Mac.
TIMESTAMP=$(python -c 'from time import time; print(int(round(time() * 1000)))')

# This is not a real chainspec variable. Set it to "true" to make the genesis era start
# align to rounded era durations, e.g. with 1hour eras they'd start at 0:00, 1:00, 2:00;
# if it's "false" then they start from whatever the current timestamp is.
TRUNCATE=${CL_CHAINSPEC_HIGHWAY_GENESIS_ERA_START_TRUNCATE:-"false"}

CL_CHAINSPEC_HIGHWAY_ERA_DURATION=${CL_CHAINSPEC_HIGHWAY_ERA_DURATION:-"5minutes"}
CL_CHAINSPEC_HIGHWAY_BOOKING_DURATION=${CL_CHAINSPEC_HIGHWAY_BOOKING_DURATION:-"8minutes"}
CL_CHAINSPEC_HIGHWAY_ENTROPY_DURATION=${CL_CHAINSPEC_HIGHWAY_ENTROPY_DURATION:-"1minute"}
CL_CHAINSPEC_HIGHWAY_VOTING_PERIOD_DURATION=${CL_CHAINSPEC_HIGHWAY_VOTING_PERIOD_DURATION:-"1minute"}

if [ "${TRUNCATE}" == "true" ]; then
  # Extraction duration like 10minutes, 1hour, 7days into 10m, 1h, 7d (doesn't handle milliseconds).
  DURATION=$(echo $CL_CHAINSPEC_HIGHWAY_ERA_DURATION | sed -r 's/([0-9]+)([smdh]).*|.*/\1 \2/')
  if [ -z "$DURATION" ]; then
    echo "Invalid duration: ${CL_CHAINSPEC_HIGHWAY_ERA_DURATION}; should be a numeric length and a unit of [seconds | minutes | hours | days]"
  fi
  LENGTH=$(echo $DURATION | cut -d' ' -f1)
  UNIT=$(echo $DURATION | cut -d' ' -f2)

  case "${UNIT}" in
    s)
      UNIT_MILLIS=1000
      ;;
    m)
      UNIT_MILLIS=$((60*1000))
      ;;
    h)
      UNIT_MILLIS=$((60*60*1000))
      ;;
    d)
      UNIT_MILLIS=$((24*60*60*1000))
      ;;
    *)
      echo "Invalid time unit: ${UNIT}"; exit 1
      ;;
  esac

  # Calculate the value of the genesis era epoch so that it is active *now*.
  # NOTE: An era of 7 days will start today (i.e. yesterday midnight), not at last Sunday midnight.
  CL_CHAINSPEC_HIGHWAY_GENESIS_ERA_START=$(($TIMESTAMP - $TIMESTAMP % $UNIT_MILLIS))
else
  CL_CHAINSPEC_HIGHWAY_GENESIS_ERA_START=${TIMESTAMP}
fi

# Print all the environment variables that can override the chainspec.
for var in $(compgen -v CL_CHAINSPEC_HIGHWAY_); do
  echo $var=${!var}
done
