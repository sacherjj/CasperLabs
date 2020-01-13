SCRIPTS_DIRECTORY="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
export ROOT_DIRECTORY=${SCRIPTS_DIRECTORY}/..

export CL_CASPER_AUTO_PROPOSE_ENABLED=true
NUMBER_OF_NODES=${CL_CASPER_NUM_VALIDATORS:-3}

docker network create casperlabs

cd $ROOT_DIRECTORY/hack/docker

for i in $(seq 0 ${NUMBER_OF_NODES});
do
   make node-$i/down
done

for i in $(seq 0 ${NUMBER_OF_NODES});
do
   make node-$i/up
done
