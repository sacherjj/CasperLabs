#!/usr/bin/env bash

# Adding display of images to debug issues with CI run
echo Available Images:
docker network prune -f

set -e
if [[ -n $DRONE_BUILD_NUMBER ]]; then
    export TAG_NAME=DRONE-${DRONE_BUILD_NUMBER}
else
    export TAG_NAME="latest"
fi

# Passing all arguments to this script into docker startup for run_tests.sh
export TEST_RUN_ARGS="$@"

# Parse out unique_run_number arg to use number on unique elements needed for running in docker.
while [[ $# -gt 0 ]]
do
key=$1

case $key in
    --unique_run_num)
    export UNIQUE_RUN_NUM=$2
    shift # past argument
    shift # past value
    ;;
    *)    # unknown option
    shift # past argument
    ;;
esac
done

echo "UNIQUE_RUN_NUM  = ${UNIQUE_RUN_NUM}"
RUN_NAME="RUN${UNIQUE_RUN_NUM}"
echo "RUN_NAME = ${RUN_NAME}"
RUN_TAG_NAME="${TAG_NAME}-${UNIQUE_RUN_NUM}"
echo "RUN_TAG_NAME = ${RUN_TAG_NAME}"
# We need networks for the Python Client to talk directly to the DockerNode.
# We cannot share a network as we might have DockerNodes partitioned.
# This number of networks is the count of CasperLabNodes we can have active at one time.
MAX_NODE_COUNT=5

cleanup() {
    echo "Removing networks for Python Client..."
    for num in $(seq 0 $MAX_NODE_COUNT)
    do
        # Network might get tore down with docker-compose rm, so "|| true" to ignore failure
        docker network rm "cl-${RUN_TAG_NAME}-${num}" || true
    done

    docker network prune --force || true

    docker-compose rm --force || true

    # Eliminate this for next run
    cd ..
    rm -r "${RUN_NAME}"
}
trap cleanup 0

echo "Setting up networks for Python Client..."
for num in $(seq 0 $MAX_NODE_COUNT)
do
    docker network create "cl-${RUN_TAG_NAME}-${num}"
done

# Need to make network names in docker-compose.yml match tag based network.
# Using ||TAG|| as replacable element in docker-compose.yml.template
mkdir "${RUN_NAME}" || true
cp Dockerfile "${RUN_NAME}/"
# Replacing tags which need UNIQUE_RUN_NUM
sed 's/||TAG||/'"${RUN_TAG_NAME}"'/g' docker-compose.yml.template > "${RUN_NAME}/docker-compose.yml"
# Replacing IMAGE_TAG which should not have UNIQUE_RUN_NUM
sed -i.bak 's/||IMAGE_TAG||/'"${TAG_NAME}"'/g' "${RUN_NAME}/docker-compose.yml"

pwd
cd "${RUN_NAME}"
pwd
ls
docker-compose up --exit-code-from test --abort-on-container-exit
result_code=$?
exit ${result_code}
