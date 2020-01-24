from typing import Generator

import docker as docker_py
import pytest
import shutil

from docker.client import DockerClient

from casperlabs_local_net.common import make_tempdir, random_string
from casperlabs_local_net.cli import CLI, DockerCLI
from casperlabs_local_net.casperlabs_network import (
    CustomConnectionNetwork,
    OneNodeNetwork,
    ThreeNodeNetwork,
    TwoNodeNetwork,
    TwoNodeNetworkWithGeneratedKeys,
    PaymentNodeNetwork,
    PaymentNodeNetworkWithNoMinBalance,
    TrillionPaymentNodeNetwork,
    OneNodeWithGRPCEncryption,
    OneNodeWithClarity,
    EncryptedTwoNodeNetwork,
    ReadOnlyNodeNetwork,
    InterceptedTwoNodeNetwork,
    TwoNodeWithDifferentAccountsCSVNetwork,
    NetworkWithTaggedDev,
    OneNodeNetworkWithChainspecUpgrades,
    ThreeNodeNetworkWithTwoBootstraps,
    OneNodeWithAutoPropose,
)


@pytest.fixture(scope="function")
def temp_dir():
    directory = make_tempdir(random_string(6))
    yield directory
    shutil.rmtree(directory)


@pytest.fixture(scope="session")
def docker_client_fixture() -> Generator[DockerClient, None, None]:
    docker_client = docker_py.from_env()
    try:
        yield docker_client
    finally:
        docker_client.networks.prune()
        docker_client.volumes.prune()
        docker_client.containers.prune()


@pytest.fixture(scope="module")
def one_node_network(docker_client_fixture):
    with OneNodeNetwork(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture(scope="module")
def read_only_node_network(docker_client_fixture):
    with ReadOnlyNodeNetwork(docker_client_fixture) as onn:
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
def encrypted_one_node_network(docker_client_fixture):
    with OneNodeWithGRPCEncryption(docker_client_fixture) as net:
        net.create_cl_network()
        yield net


@pytest.fixture(scope="module")
def one_node_network_with_clarity(docker_client_fixture):
    with OneNodeWithClarity(docker_client_fixture) as net:
        net.create_cl_network()
        yield net


@pytest.fixture(scope="module")
def one_node_network_with_auto_propose(docker_client_fixture):
    with OneNodeWithAutoPropose(docker_client_fixture) as net:
        net.create_cl_network()
        yield net


@pytest.fixture()
def two_node_network(docker_client_fixture):
    with TwoNodeNetwork(docker_client_fixture) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture()
def two_node_network_with_python_generated_keys(docker_client_fixture):
    with TwoNodeNetworkWithGeneratedKeys(docker_client_fixture, CLI) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture()
def two_node_network_with_scala_generated_keys(docker_client_fixture):
    with TwoNodeNetworkWithGeneratedKeys(docker_client_fixture, DockerCLI) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture()
def two_node_with_different_accounts_csv_network(docker_client_fixture):
    with TwoNodeWithDifferentAccountsCSVNetwork(docker_client_fixture) as tnn:
        yield tnn


@pytest.fixture(scope="module")
def encrypted_two_node_network(docker_client_fixture):
    with EncryptedTwoNodeNetwork(docker_client_fixture) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture(scope="module")
def three_node_network(docker_client_fixture):
    with ThreeNodeNetwork(docker_client_fixture) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture(scope="module")
def three_node_network_with_two_bootstraps(docker_client_fixture):
    with ThreeNodeNetworkWithTwoBootstraps(docker_client_fixture) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture(scope="module")
def intercepted_two_node_network(docker_client_fixture):
    with InterceptedTwoNodeNetwork(docker_client_fixture) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture()
def chainspec_upgrades_network_major(docker_client_fixture):
    with OneNodeNetworkWithChainspecUpgrades(
        docker_client_fixture, chainspec_directory="test-chainspec"
    ) as net:
        net.create_cl_network()
        yield net


@pytest.fixture()
def chainspec_upgrades_network_minor(docker_client_fixture):
    with OneNodeNetworkWithChainspecUpgrades(
        docker_client_fixture, chainspec_directory="test-chainspec-minor"
    ) as net:
        net.create_cl_network()
        yield net


@pytest.fixture()
def chainspec_upgrades_network_etc(docker_client_fixture):
    with OneNodeNetworkWithChainspecUpgrades(
        docker_client_fixture, etc_casperlabs_directory="etc_casperlabs"
    ) as net:
        net.create_cl_network()
        yield net


@pytest.fixture(scope="module")
def nodes(three_node_network):
    return three_node_network.docker_nodes


@pytest.fixture(scope="module")
def node(one_node_network):
    return one_node_network.docker_nodes[0]


@pytest.fixture(scope="module")
def network_with_dev(docker_client_fixture):
    with NetworkWithTaggedDev(docker_client_fixture) as nwtd:
        nwtd.create_cl_network()
        yield nwtd


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
