import contextlib
import dataclasses
import logging
import os
import random
import shutil
import tempfile
import sys
from pathlib import Path

from typing import TYPE_CHECKING, Generator, List

import docker as docker_py
import pytest

from .cl_node.common import KeyPair, TestingContext
from .cl_node.pregenerated_keypairs import PREGENERATED_KEYPAIRS
from .cl_node.casperlabsnode import docker_network_with_started_bootstrap, HOST_GENESIS_DIR, HOST_MOUNT_DIR


if TYPE_CHECKING:
    from docker.client import DockerClient
    from _pytest.config.argparsing import Parser


@dataclasses.dataclass
class CommandLineOptions:
    peer_count: int
    node_startup_timeout: int
    network_converge_timeout: int
    receive_timeout: int
    command_timeout: int
    blocks: int
    mount_dir: str


def pytest_addoption(parser: "Parser") -> None:
    parser.addoption("--peer-count", action="store", default="2", help="number of peers in the network (excluding bootstrap node)")
    parser.addoption("--start-timeout", action="store", default="0", help="timeout in seconds for starting a node. Defaults to 30 + peer_count * 10")
    parser.addoption("--converge-timeout", action="store", default="0", help="timeout in seconds for network converge. Defaults to 200 + peer_count * 10")
    parser.addoption("--receive-timeout", action="store", default="0", help="timeout in seconds for receiving a message. Defaults to 10 + peer_count * 10")
    parser.addoption("--command-timeout", action="store", default="10", help="timeout in seconds for executing an cl_node call (Examples: propose, show-logs etc.). Defaults to 10s")
    parser.addoption("--blocks", action="store", default="1", help="the number of deploys per test deploy")
    parser.addoption("--mount-dir", action="store", default=None, help="globally accesible directory for mounting between containers")


def make_timeout(peer_count: int, value: int, base: int, peer_factor: int = 10) -> int:
    if value > 0:
        return value
    return base + peer_count * peer_factor


def get_resources_folder() -> Path:
    """ This will return the resources folder that is copied into the correct location for testing """
    cur_path = Path(os.path.realpath(__file__)).parent
    while cur_path.name != 'integration-testing':
        cur_path = cur_path.parent
    return cur_path / 'resources'


def setup_testing_environment() -> None:
    """ Global testing setup in Python rather than run_tests.sh """
    resources_source_path = get_resources_folder()
    shutil.copytree(resources_source_path, HOST_MOUNT_DIR)


def teardown_testing_environment() -> None:
    """ Global testing teardown in Python rather than run_tests.sh """
    shutil.rmtree(HOST_MOUNT_DIR)


@pytest.fixture(scope='session', autouse=True)
def session_testing_environment():
    """
    Using a single environment in Python rather than run_test.sh, allows running tests directly from python for debug
    if needed.
    """
    setup_testing_environment()
    yield None
    teardown_testing_environment()


@pytest.fixture(scope='session')
def command_line_options_fixture(request):
    peer_count = int(request.config.getoption("--peer-count"))
    start_timeout = int(request.config.getoption("--start-timeout"))
    converge_timeout = int(request.config.getoption("--converge-timeout"))
    receive_timeout = int(request.config.getoption("--receive-timeout"))
    command_timeout = int(request.config.getoption("--command-timeout"))
    blocks = int(request.config.getoption("--blocks"))
    mount_dir = request.config.getoption("--mount-dir")

    command_line_options = CommandLineOptions(
        peer_count=peer_count,
        node_startup_timeout=180,
        network_converge_timeout=make_timeout(peer_count, converge_timeout, 200, 10),
        receive_timeout=make_timeout(peer_count, receive_timeout, 10, 10),
        command_timeout=command_timeout,
        blocks=blocks,
        mount_dir=mount_dir,
    )

    yield command_line_options


def create_genesis_folder() -> None:
    try:
        if os.path.exists(HOST_GENESIS_DIR):
            shutil.rmtree(HOST_GENESIS_DIR)
        os.makedirs(HOST_GENESIS_DIR)
    except Exception as ex:
        sys.exit(1)
        logging.exception(f"An exception occured while creating the folder {HOST_GENESIS_DIR}: {ex}")


def create_bonds_file(validator_keys: List[KeyPair]) -> str:
    (fd, _file) = tempfile.mkstemp(prefix="bonds-", suffix=".txt", dir="/tmp")
    try:
        with os.fdopen(fd, "w") as f:
            for pair in validator_keys:
                bond = random.randint(1, 100)
                f.write("{} {}\n".format(pair.public_key, bond))
        return _file
    except Exception as ex:
        logging.exception(f"An exception occured: {ex}")


@pytest.fixture(scope='session')
def docker_client_fixture() -> Generator["DockerClient", None, None]:
    docker_client = docker_py.from_env()
    try:
        yield docker_client
    finally:
        docker_client.volumes.prune()
        docker_client.networks.prune()


@contextlib.contextmanager
def testing_context(command_line_options_fixture, docker_client_fixture, bootstrap_keypair: KeyPair = None, peers_keypairs: List[KeyPair] = None) -> Generator[TestingContext, None, None]:
    if bootstrap_keypair is None:
        bootstrap_keypair = PREGENERATED_KEYPAIRS[0]
    if peers_keypairs is None:
        peers_keypairs = PREGENERATED_KEYPAIRS[1:]

    # Using pre-generated validator key pairs by cl_node. We do this because warning below  with python generated keys
    # WARN  io.casperlabs.casper.Validate$ - CASPER: Ignoring block 2cb8fcc56e... because block creator 3641880481... has 0 weight
    validator_keys = [kp for kp in [bootstrap_keypair] + peers_keypairs[0: command_line_options_fixture.peer_count + 1]]
    create_genesis_folder()
    bonds_file = create_bonds_file(validator_keys)
    peers_keypairs = validator_keys
    context = TestingContext(
        bonds_file=bonds_file,
        bootstrap_keypair=bootstrap_keypair,
        peers_keypairs=peers_keypairs,
        docker=docker_client_fixture,
        **dataclasses.asdict(command_line_options_fixture),
    )
    yield context


@pytest.yield_fixture(scope='module')
def started_standalone_bootstrap_node(command_line_options_fixture, docker_client_fixture):
    with testing_context(command_line_options_fixture, docker_client_fixture) as context:
        with docker_network_with_started_bootstrap(context=context) as bootstrap_node:
            yield bootstrap_node
