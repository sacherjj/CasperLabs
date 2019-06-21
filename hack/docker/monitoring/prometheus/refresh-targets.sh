#!/usr/bin/env sh

set -e

# Collect all the node hosts to be monitored by Prometheus.
# https://prometheus.io/docs/guides/file-sd/

PORT=${CL_SERVER_HTTP_PORT:-40403}
HOSTS=$(docker ps --format '{{.Names}}' \
    | grep -e ^node- \
    | sort \
    | awk -v ORS=', ' '{print "\""$1":'$PORT'\""}' \
    | sed 's/, $//')

cat <<EOF > $(dirname $0)/targets.yml
- labels:
    job: node
  targets: [${HOSTS}]
EOF
