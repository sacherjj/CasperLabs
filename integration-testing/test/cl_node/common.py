import dataclasses
import os
import random
import string
import tempfile
import typing
from pathlib import Path

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
    docker: 'DockerClient'


def get_root_test_path():
    cur_path = Path(os.path.realpath(__file__)).parent
    while cur_path.name != 'integration-testing':
        cur_path = cur_path.parent
    return cur_path


def random_string(length: int) -> str:
    return ''.join(random.choice(string.ascii_letters) for _ in range(length)).lower()


def make_tempfile(prefix: str, content: str) -> str:
    fd, path = tempfile.mkstemp(dir="/tmp", prefix=prefix)

    with os.fdopen(fd, 'w') as tmp:
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


class WaitTimeoutError(Exception):
    def __init__(self, predicate: 'PredicateProtocol', timeout: int) -> None:
        super().__init__()
        self.predicate = predicate
        self.timeout = timeout
