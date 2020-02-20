#!/usr/bin/env bash

set -e

if [[ -n $DRONE_BUILD_NUMBER ]]; then
    export TAG_NAME=DRONE-${DRONE_BUILD_NUMBER}
    # Using last 2 digits of build number for 2nd octet of subnet
    # This will be unique across runs close in time.  We should
    # never have a run within 100 of the next one.
    padded_drone="0${DRONE_BUILD_NUMBER}"
    # If I don't pad, I'm getting nothing off the -2 as it is outside of string len
    subnet_second_octet=${padded_drone: -2}
else
    export TAG_NAME="latest"
    subnet_second_octet=0
fi

# Parse out unique_run_number arg.
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
    echo "Unknown argument: ${key}"
    exit 1
    shift # past argument
    ;;
esac
done

# Make 0 if not set
export UNIQUE_RUN_NUM="${UNIQUE_RUN_NUM:-0}"

echo "UNIQUE_RUN_NUM  = ${UNIQUE_RUN_NUM}"
RUN_NAME="RUN${UNIQUE_RUN_NUM}-${TAG_NAME}"
echo "RUN_NAME = ${RUN_NAME}"
RUN_TAG_NAME="${TAG_NAME}-${UNIQUE_RUN_NUM}"
echo "RUN_TAG_NAME = ${RUN_TAG_NAME}"

# We need networks for the Python Client to talk directly to the DockerNode.
# We cannot share a network as we might have DockerNodes partitioned.
# This number of networks is the count of CasperLabNodes we can have active at one time.
MAX_NODE_COUNT=5

cleanup() {
    docker-compose rm --force || true

    echo "Removing networks for Python Client..."
    for num in $(seq 0 $MAX_NODE_COUNT)
    do
        # Network might get tore down with docker-compose rm, so "|| true" to ignore failure
        docker network rm "cl-${RUN_TAG_NAME}-${num}" || true
    done

    # Eliminate docker-compose for next run
    cd ..
    rm -r "${RUN_NAME}"
}
trap cleanup 0

echo "Setting up networks for Python Client..."
for num in $(seq 0 $MAX_NODE_COUNT)
do
    # Both UNIQUE_RUN_NUM and num will be single digit.
    third_octet="${UNIQUE_RUN_NUM}${num}"
    subnet="192.${subnet_second_octet}.${third_octet}.0/24"
    net_name="cl-${RUN_TAG_NAME}-${num}"
    echo "Creating docker network '${net_name}' using subnet: ${subnet}..."
    docker network create --subnet "${subnet}" "${net_name}"
    docker network inspect "${net_name}" | grep Subnet
done

# Need to make network names in docker-compose.yml match tag based network.
# Using ||TAG|| as replacable element in docker-compose.yml.template
mkdir "${RUN_NAME}" || true
cp Dockerfile "${RUN_NAME}/"
# Replacing tags in docker-compose
sed -e 's/||TAG||/'"${RUN_TAG_NAME}"'/g' -e 's/||IMAGE_TAG||/'"${TAG_NAME}"'/g' docker-compose.yml.template > "${RUN_NAME}/docker-compose.yml"

cd "${RUN_NAME}"
docker-compose up --exit-code-from test --abort-on-container-exit
result_code=$?
exit ${result_code}
