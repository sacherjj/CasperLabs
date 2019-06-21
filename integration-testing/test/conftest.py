from typing import TYPE_CHECKING, Generator

import docker as docker_py
import pytest

from .cl_node.casperlabs_network import (
    CustomConnectionNetwork,
    OneNodeNetwork,
    ThreeNodeNetwork,
    TwoNodeNetwork,
)
from .cl_node.wait import wait_for_blocks_count_at_least


if TYPE_CHECKING:
    from docker.client import DockerClient


@pytest.fixture(scope='session')
def docker_client_fixture() -> Generator["DockerClient", None, None]:
    docker_client = docker_py.from_env()
    try:
        yield docker_client
    finally:
        docker_client.volumes.prune()
        docker_client.networks.prune()


@pytest.fixture()
def one_node_network(docker_client_fixture):
    with OneNodeNetwork(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture(scope='module')
def one_node_network_module_scope(docker_client_fixture):
    with OneNodeNetwork(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture()
def two_node_network(docker_client_fixture):
    with TwoNodeNetwork(docker_client_fixture) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture()
def three_node_network(docker_client_fixture):
    with ThreeNodeNetwork(docker_client_fixture) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture(scope='module')
def three_node_network_module_scope(docker_client_fixture):
    with ThreeNodeNetwork(docker_client_fixture) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture()
def node(one_node_network):
    with one_node_network as network:
        # Wait for the genesis block reaching each node.
        for node in network.docker_nodes:
            wait_for_blocks_count_at_least(node, 1, 1, node.timeout)
        yield network.docker_nodes[0]


@pytest.fixture()
def engine(one_node_network):
    with one_node_network as network:
        yield network.execution_engines[0]

@pytest.fixture()
def star_network(docker_client_fixture):
    with CustomConnectionNetwork(docker_client_fixture) as ccn:
        node_count = 4
        network_connections = [[0, n] for n in range(1, 4)]
        ccn.create_cl_network(node_count=node_count, network_connections=network_connections)
        yield ccn


@pytest.hookimpl(tryfirst=True)
def pytest_keyboard_interrupt(excinfo):
    docker_client = docker_py.from_env()
    docker_client.containers.prune()
    docker_client.volumes.prune()
    docker_client.networks.prune()
    pytest.exit("Keyboard Interrupt occurred, So stopping the execution of tests.")
