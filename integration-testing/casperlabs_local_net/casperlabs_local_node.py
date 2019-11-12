import time
import docker
import traceback
from casperlabs_local_net.casperlabs_network import InterceptedOneNodeNetwork


def prune_docker(docker_api):
    docker_api.volumes.prune()
    docker_api.networks.prune()
    docker_api.containers.prune()


def main():
    docker_api = docker.from_env()
    prune_docker(docker_api)
    network = InterceptedOneNodeNetwork(docker_api)
    try:
        network.create_cl_network()
        print("Node and proxy is running, press Ctrl-C to stop it.")
        node = network.docker_nodes[0]
        logs = ""
        while True:
            new_logs = node.logs()
            if new_logs != logs:
                print(new_logs[len(logs) :])
                logs = new_logs
            time.sleep(0.1)
    except KeyboardInterrupt as e:
        print("\nControl-C was pressed")
        traceback.print_exc()
        print(str(e))
    finally:
        print("Prune docker volumes, networks and containers...")
        prune_docker(docker_api)
        print("See you later!")
