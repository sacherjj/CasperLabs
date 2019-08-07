import dataclasses
import os
import random
import string
import tempfile
import typing
import re

from docker.client import DockerClient


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


def extract_deploy_hash_from_deploy_output(deploy_output: str):
    """We're getting back something along the lines of:

    Success! Deploy 7a1235d50ddb6299525f5c90af5be24fa949a7497041146d2ed943da7300c57f deployed.
    """
    match = re.match(r'Success! Deploy ([0-9a-f]+) deployed.', deploy_output.strip())
    if match is None:
        raise Exception(f'Error extracting deploy hash: {deploy_output}')
    return match.group(1)


class Network:
    def __init__(self, network, bootstrap, peers, engines):
        self.network = network
        self.bootstrap = bootstrap
        self.peers = peers
        self.nodes = [bootstrap] + peers
        self.engines = engines
