import os
import time
import docker
import traceback
from casperlabs_local_net.casperlabs_network import InterceptedOneNodeNetwork
from casperlabs_local_net.docker_cleanup_assurance import cleanup


def main():
    docker_api = docker.from_env()
    tag_name = os.environ.get("TAG_NAME") or "test"
    cleanup(tag_name)
    network = InterceptedOneNodeNetwork(docker_api)
    try:
        network.create_cl_network()
        print("Node and proxy is running, press Ctrl-C to stop it.")
        node = network.docker_nodes[0]
        read_logs_length = 0
        while True:
            new_logs_length = len(node.logs())
            if new_logs_length > read_logs_length:
                read_logs_length = new_logs_length
                print(node.logs()[read_logs_length:])
            time.sleep(0.1)
    except KeyboardInterrupt as e:
        print("\nControl-C was pressed")
        traceback.print_exc()
        print(str(e))
    finally:
        print("Prune docker volumes, networks and containers...")
        cleanup(tag_name)
        print("See you later!")
