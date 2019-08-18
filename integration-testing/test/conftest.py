from typing import Generator

import docker as docker_py
import pytest

from .cl_node.casperlabs_network import (
    CustomConnectionNetwork,
    OneNodeNetwork,
    ThreeNodeNetwork,
    TwoNodeNetwork,
    PaymentNodeNetwork,
    PaymentNodeNetworkWithNoMinBalance,
    PaymentNodForOnlyPaymentContract,
    TrillionPaymentNodeNetwork,
)
from docker.client import DockerClient


@pytest.fixture(scope="session")
def docker_client_fixture() -> Generator[DockerClient, None, None]:
    docker_client = docker_py.from_env()
    try:
        yield docker_client
    finally:
        docker_client.volumes.prune()
        docker_client.networks.prune()


@pytest.fixture(scope="function")
def one_node_network(docker_client_fixture):
    with OneNodeNetwork(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture(scope="function")
def one_node_network_fn(docker_client_fixture):
    with OneNodeNetwork(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture(scope="function")
def payment_node_network(docker_client_fixture):
    with PaymentNodeNetwork(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture(scope="function")
def trillion_payment_node_network(docker_client_fixture):
    with TrillionPaymentNodeNetwork(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture(scope="function")
def payment_node_network_no_min_balance(docker_client_fixture):
    with PaymentNodeNetworkWithNoMinBalance(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture(scope="function")
def payment_node_network_with_just_enough_to_run_payment_contract(
    docker_client_fixture
):
    with PaymentNodForOnlyPaymentContract(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture()
def two_node_network(docker_client_fixture):
    with TwoNodeNetwork(docker_client_fixture) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture(scope="module")
def three_node_network(docker_client_fixture):
    with ThreeNodeNetwork(docker_client_fixture) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture(scope="module")
def nodes(three_node_network):
    return three_node_network.docker_nodes


@pytest.fixture(scope="module")
def node(one_node_network):
    return one_node_network.docker_nodes[0]


@pytest.fixture(scope="module")
def engine(one_node_network):
    with one_node_network as network:
        yield network.execution_engines[0]


@pytest.fixture()
def star_network(docker_client_fixture):
    with CustomConnectionNetwork(docker_client_fixture) as ccn:
        node_count = 4
        network_connections = [[0, n] for n in range(1, 4)]
        ccn.create_cl_network(
            node_count=node_count, network_connections=network_connections
        )
        yield ccn


@pytest.hookimpl(tryfirst=True)
def pytest_keyboard_interrupt(excinfo):
    docker_client = docker_py.from_env()
    docker_client.containers.prune()
    docker_client.volumes.prune()
    docker_client.networks.prune()
    pytest.exit("Keyboard Interrupt occurred, So stopping the execution of tests.")
