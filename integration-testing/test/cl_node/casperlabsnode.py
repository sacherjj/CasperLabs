import contextlib
import logging
import os
import re
from typing import TYPE_CHECKING, Dict, Generator, List, Optional, Tuple, Union

from docker.client import DockerClient

from .common import random_string
from .errors import (
    UnexpectedProposeOutputFormatError,
    UnexpectedShowBlocksOutputFormatError,
)


TAG = os.environ.get("TAG_NAME", None)
if TAG is None:
    TAG = "test"

DEFAULT_NODE_IMAGE = f"casperlabs/node:{TAG}"
DEFAULT_ENGINE_IMAGE = f"casperlabs/execution-engine:{TAG}"
DEFAULT_CLIENT_IMAGE = f"casperlabs/client:{TAG}"
CL_NODE_BINARY = '/opt/docker/bin/bootstrap'
CL_NODE_DIRECTORY = "/root/.casperlabs"
CL_NODE_DEPLOY_DIR = f"{CL_NODE_DIRECTORY}/deploy"
CL_GENESIS_DIR = f'{CL_NODE_DIRECTORY}/genesis'
CL_SOCKETS_DIR = f'{CL_NODE_DIRECTORY}/sockets'
CL_BOOTSTRAP_DIR = f"{CL_NODE_DIRECTORY}/bootstrap"
CL_BONDS_FILE = f"{CL_GENESIS_DIR}/bonds.txt"
GRPC_SOCKET_FILE = f"{CL_SOCKETS_DIR}/.casper-node.sock"
EXECUTION_ENGINE_COMMAND = ".casperlabs/sockets/.casper-node.sock"

HELLO_NAME = "test_helloname.wasm"
HELLO_WORLD = "test_helloworld.wasm"
COUNTER_CALL = "test_countercall.wasm"
MAILING_LIST_CALL = "test_mailinglistcall.wasm"
COMBINED_CONTRACT = "test_combinedcontractsdefine.wasm"


HOST_MOUNT_DIR = f"/tmp/resources_{TAG}"
HOST_GENESIS_DIR = f"{HOST_MOUNT_DIR}/genesis"
HOST_BOOTSTRAP_DIR = f"{HOST_MOUNT_DIR}/bootstrap_certificate"


def extract_block_count_from_show_blocks(show_blocks_output: str) -> int:
    lines = show_blocks_output.splitlines()
    prefix = 'count: '
    interesting_lines = [l for l in lines if l.startswith(prefix)]
    if len(interesting_lines) != 1:
        raise UnexpectedShowBlocksOutputFormatError(show_blocks_output)
    line = interesting_lines[0]
    count = line[len(prefix):]
    try:
        result = int(count)
    except ValueError:
        raise UnexpectedShowBlocksOutputFormatError(show_blocks_output)
    return result


def extract_block_hash_from_propose_output(propose_output: str):
    """We're getting back something along the lines of:

    Response: Success! Block a91208047c... created and added.\n
    """
    match = re.match(r'Response: Success! Block ([0-9a-f]+)\.\.\. created and added.', propose_output.strip())
    if match is None:
        raise UnexpectedProposeOutputFormatError(propose_output)
    return match.group(1)


def make_get_state_client_name() -> str:
    return f"get_state.{random_string(5)}"


def get_contract_state(
    *,
    docker_client: "DockerClient",
    network_name: str,
    target_host_name: str,
    port: int,
    _type: str,
    key: str,
    path: str,
    block_hash: str,
    image: str = DEFAULT_CLIENT_IMAGE
):
    name = make_get_state_client_name()
    command = f'--host {target_host_name} --port {port} query-state -t {_type} -k {key} -p "{path}" -b {block_hash}'
    logging.info(command)
    output = docker_client.containers.run(
        image,
        name=name,
        user='root',
        auto_remove=True,
        command=command,
        network=network_name,
        hostname=name
    )
    return output
