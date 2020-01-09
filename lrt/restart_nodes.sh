DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
export ROOT_DIRECTORY=$DIR/..

export CL_CASPER_AUTO_PROPOSE_ENABLED=true

docker network create casperlabs

cd $ROOT_DIRECTORY/hack/docker

for i in $(seq 0 3);
do
   make node-$i/down
done

for i in $(seq 0 3);
do
   make node-$i/up
done
