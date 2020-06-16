import tempfile
from pathlib import Path
import os
import pytest

from casperlabs_client import CasperLabsClient
from casperlabs_client.abi import ABI
from casperlabs_client.key_holders import ED25519Key
from casperlabs_client.commands import (
    show_peers_cmd,
    visualize_dag_cmd,
    show_blocks_cmd,
    show_block_cmd,
)
from casperlabs_client.consts import SUPPORTED_KEY_ALGORITHMS


THIS_DIRECTORY = Path(os.path.dirname(os.path.realpath(__file__)))
WASM_DIRECTORY = (
    THIS_DIRECTORY.parent.parent
    / "execution-engine"
    / "target"
    / "wasm32-unknown-unknown"
    / "release"
)
HACK_DOCKER_DIRECTORY = THIS_DIRECTORY.parent.parent / "hack" / "docker"
ACCOUNTS_CSV = (
    HACK_DOCKER_DIRECTORY / ".casperlabs" / "chainspec" / "genesis" / "accounts.csv"
)
NODE_0_PRIVATE_PEM_PATH = (
    HACK_DOCKER_DIRECTORY
    / ".casperlabs"
    / "nodes"
    / "nodes-0"
    / "validator-private.pem"
)


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
def genesis_account_and_hash():
    """ Returns tuple of data from genesis account using accounts.csv

     :returns: (public_key_hex, public_key_pem_path, account_hash_hex)
     """
    with open(ACCOUNTS_CSV, "r") as f:
        line = f.readline()
        account_public_key_hex = line.split(",")[0]
    key_holder = ED25519Key(public_key=bytes.fromhex(account_public_key_hex))
    with tempfile.TemporaryDirectory("rb") as td:
        key_path = Path(td) / "account-public.pem"
        with open(key_path, "wb") as f:
            f.write(key_holder.public_key_pem)
        return account_public_key_hex, key_path, key_holder.account_hash_hex


@pytest.fixture(scope="session")
def hack_docker_account_hashes():
    accounts = []
    for file_num in range(3):
        file_path = (
            HACK_DOCKER_DIRECTORY / "keys" / f"account-{file_num}" / "account-id-hex"
        )
        with open(file_path, "rb") as f:
            account_hash_bytes = f.read().strip()
            account_hash = account_hash_bytes.decode("UTF_8")
            accounts.append(account_hash)
    return accounts


def test_account_hash(casperlabs_client):
    """ Use account generated key and hash to test python hash """
    account_path = HACK_DOCKER_DIRECTORY / "keys" / "account-0"
    key_holder = ED25519Key.from_public_key_path(account_path / "account-public.pem")
    with open(account_path / "account-id-hex", "rb") as f:
        account_hash_hex = f.read().strip().decode("UTF_8")
    expected_hash = key_holder.account_hash.hex()
    assert account_hash_hex == expected_hash


def test_balance(casperlabs_client, genesis_account_and_hash):
    _, _, genesis_account_hash_hex = genesis_account_and_hash
    block_hash = get_valid_block_hash_str(casperlabs_client)
    result = casperlabs_client.balance(genesis_account_hash_hex, block_hash)
    assert result > 0


def test_deploy_for_faucet(casperlabs_client, account_keys_directory):
    faucet_wasm_path = WASM_DIRECTORY / "faucet.wasm"
    private_key_pem_path = account_keys_directory / "ED25519-private.pem"
    key_holder = ED25519Key.from_private_key_path(private_key_pem_path)
    session_args = ABI.args(
        [
            ABI.account("account", key_holder.account_hash_hex),
            ABI.big_int("amount", 1000000),
        ]
    )
    result = casperlabs_client.deploy(
        private_key=private_key_pem_path,
        session=faucet_wasm_path,
        session_args=ABI.args_to_json(session_args),
    )
    print(result)


def test_transfer(casperlabs_client, genesis_account_and_hash, account_keys_directory):
    # genesis_public_key_hex, _, genesis_account_hash_hex = genesis_account_and_hash
    # casperlabs_client.transfer()

    pass


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
