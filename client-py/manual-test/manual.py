import tempfile
from pathlib import Path
import os
import pytest

from casperlabs_client import CasperLabsClient
from casperlabs_client.key_holders import ED25519Key
from casperlabs_client.commands import show_peers_cmd, visualize_dag_cmd, show_blocks_cmd, show_block_cmd
from casperlabs_client.consts import SUPPORTED_KEY_ALGORITHMS


THIS_DIRECTORY = Path(os.path.dirname(os.path.realpath(__file__)))


@pytest.fixture()
def casperlabs_client() -> CasperLabsClient:
    return CasperLabsClient()


@pytest.fixture(scope="session")
def account_keys_directory():
    with tempfile.TemporaryDirectory() as directory:
        client = CasperLabsClient()
        for key_algorithm in SUPPORTED_KEY_ALGORITHMS:
            client.keygen(
                directory, algorithm=key_algorithm, filename_prefix=key_algorithm
            )
        yield Path(directory)


@pytest.fixture(scope="session")
def hack_docker_directory():
    return THIS_DIRECTORY.parent.parent / "hack" / "docker"


@pytest.fixture(scope="session")
def hack_docker_account_hashes(hack_docker_directory):
    accounts = []
    for file_num in range(3):
        file_path = hack_docker_directory / "keys" / f"account-{file_num}" / "account-id-hex"
        with open(file_path, "rb") as f:
            account_hash_bytes = f.read().strip()
            account_hash = account_hash_bytes.decode("UTF_8")
            accounts.append(account_hash)
    return accounts


def test_account_hash(casperlabs_client, hack_docker_directory):
    """ Use account generated key and hash to test python hash """
    account_path = hack_docker_directory / "keys" / "account-0"
    key_holder = ED25519Key.from_public_key_path(account_path / "account-public.pem")
    with open(account_path / "account-id-hex", "rb") as f:
        account_hash_hex = f.read().strip().decode("UTF_8")
    expected_hash = key_holder.account_hash.hex()
    assert account_hash_hex == expected_hash


# def test_balance(casperlabs_client, hack_docker_accounts):
#     block_hash = get_valid_block_hash_str(casperlabs_client)
#     result = casperlabs_client.balance(hack_docker_accounts[0], block_hash)
#     assert result == "bob"


def test_show_peers(casperlabs_client):
    response = casperlabs_client.show_peers()
    assert 'host: "node-2"' in str(response)


def test_show_peers_cli(casperlabs_client):
    show_peers_cmd.method(casperlabs_client, dict())


def get_valid_block_hash(casperlabs_client):
    """ Get a valid block hash from current hack/docker network """
    block_generator = casperlabs_client.show_blocks(depth=8)
    block = list(block_generator)[-1]
    block_hash = block.summary.block_hash
    return block_hash


def get_valid_block_hash_str(casperlabs_client):
    """ Get a valid block hash from current hack/docker network """
    block_generator = casperlabs_client.show_blocks(depth=8)
    block = list(block_generator)[-1]
    block_hash = block.summary.block_hash
    return block_hash.hex()


def test_show_block(casperlabs_client):
    block_hash = get_valid_block_hash(casperlabs_client)
    assert len(block_hash) == 32
    block_hash_hex = block_hash.hex()
    block = casperlabs_client.show_block(block_hash_base16=block_hash_hex)
    assert block.summary.block_hash == block_hash


def test_show_block_cli(casperlabs_client):
    block_hash = get_valid_block_hash(casperlabs_client)
    assert len(block_hash) == 32
    block_hash_hex = block_hash.hex()
    args = {"hash": block_hash_hex}
    show_block_cmd.method(casperlabs_client, args)


def test_show_blocks(casperlabs_client):
    block_generator = casperlabs_client.show_blocks(depth=3)
    for block in block_generator:
        assert "validator_public_key_hash: " in str(block)


def test_show_blocks_cli(casperlabs_client):
    args = {"depth": 5}
    show_blocks_cmd.method(casperlabs_client, args)


def test_vdag(casperlabs_client):
    result = casperlabs_client.visualize_dag(12)
    # Exercise generator to check for errors
    for value in result:
        assert value is not None


def test_vdag_cli(casperlabs_client):
    args = {"depth": 10}
    visualize_dag_cmd.method(casperlabs_client, args)
