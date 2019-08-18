import dataclasses
import os
import re
import random
import string
import tempfile
import typing
from pathlib import Path

from docker.client import DockerClient
from .errors import (
    UnexpectedProposeOutputFormatError,
    UnexpectedShowBlocksOutputFormatError,
)
from casperlabs_client import ABI

HELLO_NAME_CONTRACT = "test_helloname.wasm"
HELLO_WORLD = "test_helloworld.wasm"
COUNTER_CALL = "test_countercall.wasm"
MAILING_LIST_CALL = "test_mailinglistcall.wasm"
COMBINED_CONTRACT = "test_combinedcontractsdefine.wasm"
BONDING_CONTRACT = "test_bondingcall.wasm"
UNBONDING_CONTRACT = "test_unbondingcall.wasm"
INVALID_BONDING_CONTRACT = "test_invalid_bondingcall.wasm"
INVALID_UNBONDING_CONTRACT = "test_invalid_unbondingcall.wasm"
PAYMENT_PURSE_CONTRACT = "test_payment_purse.wasm"
PAYMENT_CONTRACT = "standard_payment.wasm"
MAX_PAYMENT_COST = 10000000  # ten million
MAX_PAYMENT_ABI = ABI.args([ABI.u512(MAX_PAYMENT_COST)])
CONV_RATE = 10


@dataclasses.dataclass(eq=True, frozen=True)
class KeyPair:
    private_key: str
    public_key: str


def testing_root_path() -> Path:
    cur_path = Path(os.path.realpath(__file__)).parent
    while cur_path.name != "integration-testing":
        cur_path = cur_path.parent
    return cur_path


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


def extract_deploy_hash_from_deploy_output(deploy_output: str):
    """We're getting back something along the lines of:

    Success! Deploy 7a1235d50ddb6299525f5c90af5be24fa949a7497041146d2ed943da7300c57f deployed.
    """
    match = re.match(r"Success! Deploy ([0-9a-f]+) deployed.", deploy_output.strip())
    if match is None:
        raise Exception(f"Error extracting deploy hash: {deploy_output}")
    return match.group(1)


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
