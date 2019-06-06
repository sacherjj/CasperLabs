#!/usr/bin/env bash

set -e

if [[ -n $DRONE_BUILD_NUMBER ]]; then
    export TAG_NAME=DRONE-${DRONE_BUILD_NUMBER}
else
    export TAG_NAME="test"
fi

# We need networks for the Python Client to talk directly to the DockerNode.
# We cannot share a network as we might have DockerNodes partitioned.
# This number of networks is the count of CasperLabNodes we can have active at one time.
MAX_NODE_COUNT=10

cleanup() {
    for num in $(seq 0 $MAX_NODE_COUNT)
    do
        # Network might get tore down with docker-compose rm, so "|| true" to ignore failure
        docker network rm cl-${TAG_NAME}-${num} || true
    done

    docker network prune --force || true

    docker-compose rm --force || true

    # Eliminate this for next run
    if [[ -f "docker-compose.yml" ]]; then
        rm docker-compose.yml
    fi
}
trap cleanup 0

# Build contracts before standing up docker image and they will be copied
# into the docker image when docker-compose up occurs.
echo 'Building contracts...'
cd contracts
make all
make copy
cd ..

for num in $(seq 0 $MAX_NODE_COUNT)
do
    docker network create cl-${TAG_NAME}-${num}
done

# Need to make network names in docker-compose.yml match tag based network.
# Using ||TAG|| as replaceable element in docker-compose.yml.template
sed 's/||TAG||/'"${TAG_NAME}"'/g' docker-compose.yml.template > docker-compose.yml

docker-compose up --exit-code-from test --abort-on-container-exit
result_code=$?
exit ${result_code}
