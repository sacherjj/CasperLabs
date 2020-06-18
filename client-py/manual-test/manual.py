from pathlib import Path
import os
import pytest

from casperlabs_client import CasperLabsClient


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


# @pytest.fixture(scope="session")
# def account_keys_directory():
#     with tempfile.TemporaryDirectory() as directory:
#         client = CasperLabsClient()
#         for key_algorithm in SUPPORTED_KEY_ALGORITHMS:
#             client.keygen(
#                 directory, algorithm=key_algorithm, filename_prefix=key_algorithm
#             )
#         yield Path(directory)


@pytest.fixture(scope="session")
def genesis_public_key_hex():
    with open(ACCOUNTS_CSV, "r") as f:
        line = f.readline()
        account_public_key_hex = line.split(",")[0]
    return account_public_key_hex


def test_balance(casperlabs_client, genesis_public_key_hex):
    block_hash = get_valid_block_hash_str(casperlabs_client)
    result = casperlabs_client.balance(genesis_public_key_hex, block_hash)
    assert result > 0


def test_transfer(casperlabs_client, genesis_public_key_hex, account_keys_directory):
    # genesis_public_key_hex, _, genesis_account_hash_hex = genesis_account_and_hash
    # casperlabs_client.transfer()

    pass


def test_show_peers(casperlabs_client):
    response = casperlabs_client.show_peers()
    assert 'host: "node-2"' in str(response)


def get_valid_block_hash(casperlabs_client):
    """ Get a valid block hash from current hack/docker network """
    block_generator = casperlabs_client.showBlocks(depth=8)
    block = list(block_generator)[-1]
    block_hash = block.summary.block_hash
    return block_hash


def get_valid_block_hash_str(casperlabs_client):
    """ Get a valid block hash from current hack/docker network """
    block_generator = casperlabs_client.showBlocks(depth=8)
    block = list(block_generator)[-1]
    block_hash = block.summary.block_hash
    return block_hash.hex()


def test_show_block(casperlabs_client):
    block_hash = get_valid_block_hash(casperlabs_client)
    assert len(block_hash) == 32
    block_hash_hex = block_hash.hex()
    block = casperlabs_client.showBlock(block_hash_base16=block_hash_hex)
    assert block.summary.block_hash == block_hash


def test_show_blocks(casperlabs_client):
    block_generator = casperlabs_client.showBlocks(depth=3)
    for block in block_generator:
        assert "validator_public_key_hash: " in str(block)


def test_vdag(casperlabs_client):
    result = casperlabs_client.visualizeDag(12)
    # Exercise generator to check for errors
    for value in result:
        assert value is not None
