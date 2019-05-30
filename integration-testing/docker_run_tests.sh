#!/usr/bin/env bash

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
    # Eliminate this for next run
    rm docker-compose.yml

    for num in $(seq 0 $MAX_NODE_COUNT)
    do
        docker network rm cl-${TAG_NAME}-${num}
    done
}
trap cleanup 0

error_exit()
{
    if [[ "$?" != "0" ]]; then
        echo "$1"
        exit 1
    fi
}

for num in $(seq 0 $MAX_NODE_COUNT)
do
    docker network create cl-${TAG_NAME}-${num}
done

# Need to make network names in docker-compose.yml match tag based network.
# Using ||TAG|| as replacable element in docker-compose.yml.template
sed 's/||TAG||/'"${TAG_NAME}"'/g' docker-compose.yml.template > docker-compose.yml
error_exit "Creating docker-compose.yml"

docker-compose up
error_exit "Running integration tests"
