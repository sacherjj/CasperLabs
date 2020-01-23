#!/usr/bin/env bash

# Adding display of images to debug issues with CI run
echo Available Images:
docker images

set -e
if [[ -n $DRONE_BUILD_NUMBER ]]; then
    export TAG_NAME=DRONE-${DRONE_BUILD_NUMBER}
else
    export TAG_NAME="latest"
fi

# Passing all arguments to this script into docker startup for run_tests.sh
export TEST_RUN_ARGS=$@

# Parse out unique_run_number arg to use number on unique elements needed for running in docker.
while [[ $# -gt 0 ]]
do
key="$1"

case $key in
    --unique_run_num)
    UNIQUE_RUN_NUM="$2"
    shift # past argument
    shift # past value
    ;;
    *)    # unknown option
    shift # past argument
    ;;
esac
done

echo "UNIQUE_RUN_NUM  = ${UNIQUE_RUN_NUM}"

# We need networks for the Python Client to talk directly to the DockerNode.
# We cannot share a network as we might have DockerNodes partitioned.
# This number of networks is the count of CasperLabNodes we can have active at one time.
MAX_NODE_COUNT=10

cleanup() {
    echo "Removing networks for Python Client..."
    for num in $(seq 0 $MAX_NODE_COUNT)
    do
        # Network might get tore down with docker-compose rm, so "|| true" to ignore failure
        docker network rm cl-${TAG_NAME}-RUN${UNIQUE_RUN_NUM}-${num} || true
    done

    docker network prune --force || true

    docker-compose rm --force || true

    # Eliminate this for next run
    cd ..
    rm -r RUN${UNIQUE_RUN_NUM}
}
trap cleanup 0

echo "Setting up networks for Python Client..."
for num in $(seq 0 $MAX_NODE_COUNT)
do
    docker network create cl-${TAG_NAME}-RUN${UNIQUE_RUN_NUM}-${num}
done

# Need to make network names in docker-compose.yml match tag based network.
# Using ||TAG|| as replacable element in docker-compose.yml.template
mkdir RUN${UNIQUE_RUN_NUM}
cp Dockerfile RUN${UNIQUE_RUN_NUM}/
sed 's/||TAG||/'"${TAG_NAME}-RUN${UNIQUE_RUN_NUM}"'/g' docker-compose.yml.template > RUN${UNIQUE_RUN_NUM}/docker-compose.yml

cd RUN${UNIQUE_RUN_NUM}
docker-compose up --exit-code-from test --abort-on-container-exit
result_code=$?
exit ${result_code}
