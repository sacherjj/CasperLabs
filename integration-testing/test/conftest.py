from typing import Generator

import docker as docker_py
import os
import pytest
import shutil
import logging
import time

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
    ThreeNodeHighwayNetwork,
)


NUMBER_OF_DOCKER_PRUNE_RETRIES = 10
DOCKER_PRUNE_RETRY_DELAY_SECONDS = 2


@pytest.fixture(scope="session")
def unique_run_num(pytestconfig):
    try:
        run_num = int(os.environ.get("UNIQUE_RUN_NUM"))
    except TypeError:
        run_num = 0
    return run_num


@pytest.fixture(scope="function")
def temp_dir():
    directory = make_tempdir(random_string(6))
    yield directory
    shutil.rmtree(directory)


@pytest.fixture(scope="session")
def docker_client_fixture(unique_run_num) -> Generator[DockerClient, None, None]:
    docker_client = docker_py.from_env()
    docker_client.cl_unique_run_num = unique_run_num
    try:
        yield docker_client
    finally:
        # Prunning may fail with a docker.errors.APIError: "a prune operation is already running".
        # So, we will catch exceptions here and retry few times.
        all_pruned = False
        number_of_retries = 0
        while not all_pruned and number_of_retries < NUMBER_OF_DOCKER_PRUNE_RETRIES:
            try:
                docker_client.containers.prune()
                docker_client.volumes.prune()
                docker_client.networks.prune()
                all_pruned = True
            except Exception as e:
                logging.warning(
                    "Exception in docker_client_fixture tear down.", exc_info=e
                )
                number_of_retries += 1
                logging.info(
                    f"Retrying tear down of docker_client_fixture in {DOCKER_PRUNE_RETRY_DELAY_SECONDS} seconds..."
                )
                time.sleep(DOCKER_PRUNE_RETRY_DELAY_SECONDS)
        if not all_pruned:
            logging.warning(
                f"docker_client_fixture tear down failed despite {number_of_retries} retries"
            )


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


@pytest.fixture(scope="module")
def three_node_highway_network(docker_client_fixture):
    with ThreeNodeHighwayNetwork(docker_client_fixture) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.hookimpl(tryfirst=True)
def pytest_keyboard_interrupt(excinfo):
    docker_client = docker_py.from_env()
    docker_client.containers.prune()
    docker_client.volumes.prune()
    docker_client.networks.prune()
    pytest.exit("Keyboard Interrupt occurred, So stopping the execution of tests.")
