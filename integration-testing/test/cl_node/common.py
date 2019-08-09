import dataclasses
import os
import re
import random
import string
import tempfile
import typing

from docker.client import DockerClient
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
CL_NODE_BINARY = "/opt/docker/bin/bootstrap"
CL_NODE_DIRECTORY = "/root/.casperlabs"
CL_NODE_DEPLOY_DIR = f"{CL_NODE_DIRECTORY}/deploy"
CL_GENESIS_DIR = f"{CL_NODE_DIRECTORY}/genesis"
CL_SOCKETS_DIR = f"{CL_NODE_DIRECTORY}/sockets"
CL_BOOTSTRAP_DIR = f"{CL_NODE_DIRECTORY}/bootstrap"
CL_BONDS_FILE = f"{CL_GENESIS_DIR}/bonds.txt"
GRPC_SOCKET_FILE = f"{CL_SOCKETS_DIR}/.casper-node.sock"
EXECUTION_ENGINE_COMMAND = ".casperlabs/sockets/.casper-node.sock"

HELLO_NAME = "test_helloname.wasm"
HELLO_WORLD = "test_helloworld.wasm"
COUNTER_CALL = "test_countercall.wasm"
MAILING_LIST_CALL = "test_mailinglistcall.wasm"
COMBINED_CONTRACT = "test_combinedcontractsdefine.wasm"
BONDING_CONTRACT = "test_bondingcall.wasm"
UNBONDING_CONTRACT = "test_unbondingcall.wasm"
INVALID_BONDING_CONTRACT = "test_invalid_bondingcall.wasm"
INVALID_UNBONDING_CONTRACT = "test_invalid_unbondingcall.wasm"


HOST_MOUNT_DIR = f"/tmp/resources_{TAG}"
HOST_GENESIS_DIR = f"{HOST_MOUNT_DIR}/genesis"
HOST_BOOTSTRAP_DIR = f"{HOST_MOUNT_DIR}/bootstrap_certificate"


@dataclasses.dataclass(eq=True, frozen=True)
class KeyPair:
    private_key: str
    public_key: str


@dataclasses.dataclass
class TestingContext:
    # Tell pytest this isn't Unittest class due to 'Test' name start
    __test__ = False

    peer_count: int
    node_startup_timeout: int
    network_converge_timeout: int
    receive_timeout: int
    command_timeout: int
    blocks: int
    mount_dir: str
    bonds_file: str
    bootstrap_keypair: KeyPair
    peers_keypairs: typing.List[KeyPair]
    docker: DockerClient


def random_string(length: int) -> str:
    return "".join(random.choice(string.ascii_letters) for _ in range(length)).lower()


def make_tempfile(prefix: str, content: str) -> str:
    fd, path = tempfile.mkstemp(dir="/tmp", prefix=prefix)

    with os.fdopen(fd, "w") as tmp:
        tmp.write(content)

    return path


def make_tempdir(prefix: str) -> str:
    return tempfile.mkdtemp(dir="/tmp", prefix=prefix)


class Network:
    def __init__(self, network, bootstrap, peers, engines):
        self.network = network
        self.bootstrap = bootstrap
        self.peers = peers
        self.nodes = [bootstrap] + peers
        self.engines = engines


def extract_block_count_from_show_blocks(show_blocks_output: str) -> int:
    lines = show_blocks_output.splitlines()
    prefix = "count: "
    interesting_lines = [l for l in lines if l.startswith(prefix)]
    if len(interesting_lines) != 1:
        raise UnexpectedShowBlocksOutputFormatError(show_blocks_output)
    line = interesting_lines[0]
    count = line[len(prefix) :]
    try:
        result = int(count)
    except ValueError:
        raise UnexpectedShowBlocksOutputFormatError(show_blocks_output)
    return result


def extract_block_hash_from_propose_output(propose_output: str):
    """We're getting back something along the lines of:

    Response: Success! Block a91208047c... created and added.\n
    """
    match = re.match(
        r"Response: Success! Block ([0-9a-f]+) created and added.",
        propose_output.strip(),
    )
    if match is None:
        raise UnexpectedProposeOutputFormatError(propose_output)
    return match.group(1)
