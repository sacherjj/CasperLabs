#!/usr/bin/env python3
import docker
from test.cl_node.casperlabs_network import OneNodeWithGRPCEncryption
from time import sleep
import os
import sys


def kill_dockers():
    os.system("""docker ps -q | xargs docker stop | xargs docker rm""")


def run_server():
    kill_dockers()
    docker_client = docker.from_env()
    print("Starting server")
    net = OneNodeWithGRPCEncryption(docker_client)
    net.create_cl_network()
    node = net.docker_nodes[0]
    while True:
        logs = node.logs()
        node.truncate_logs()
        if logs:
            print(logs, end="")
        sleep(1)


try:
    run_server()
except KeyboardInterrupt:
    print("Ctrl-C pressed.")
finally:
    print("Bye!")
    kill_dockers()
    sys.exit(0)
