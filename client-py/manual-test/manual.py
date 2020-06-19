from pathlib import Path
import os
import pytest

from casperlabs_client import CasperLabsClient
from casperlabs_client.abi import ABI


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
FAUCET_PRIVATE_KEY_PEM_PATH = (
    HACK_DOCKER_DIRECTORY / "keys" / "faucet-account" / "account-private.pem"
)
FAUCET_PUBLIC_KEY_PEM_PATH = (
    HACK_DOCKER_DIRECTORY / "keys" / "faucet-account" / "account-public.pem"
)

ACCOUNT_ID_HEX = "9d39b7fba47d07c1af6f711efe604a112ab371e2deefb99a613d2b3dcdfba414"
ACCOUNT_PRIVATE_PEM_PATH = THIS_DIRECTORY / "account" / "account-private-1.pem"
ACCOUNT_PUBLIC_PEM_PATH = THIS_DIRECTORY / "account" / "account-private-1.pem"
TARGET_ACCOUNT = "7777777777777777777777777777777777777777777777777777777777777777"


@pytest.fixture(scope="session")
def casperlabs_client() -> CasperLabsClient:
    return CasperLabsClient()


def faucet_fund_account(casperlabs_client, account_id_hex, amount=1000000000):
    faucet_wasm_path = WASM_DIRECTORY / "faucet.wasm"
    session_args = ABI.args(
        [
            ABI.account("account", bytes.fromhex(account_id_hex)),
            ABI.big_int("amount", amount),
        ]
    )
    deploy_hash = casperlabs_client.deploy(
        public_key=FAUCET_PUBLIC_KEY_PEM_PATH,
        private_key=FAUCET_PRIVATE_KEY_PEM_PATH,
        session=faucet_wasm_path,
        session_args=session_args,
    )
    result = casperlabs_client.showDeploy(deploy_hash, wait_for_processed=True)
    assert not result.processing_results[0].status_info.is_error
    block_hash = result.processing_results[0].block_info.summary.block_hash
    result = casperlabs_client.balance(account_id_hex, block_hash.hex())
    assert result > 0


# @pytest.fixture(scope="session")
# def faucet_funded_accounts(casperlabs_client, account_keys_directory) -> dict:
#     accounts = {}
#     for key_algorithm in SUPPORTED_KEY_ALGORITHMS:
#         private_key_pem_path = account_keys_directory / f"{key_algorithm}-private.pem"
#         key_holder = key_holders.key_holder_object(
#             algorithm=key_algorithm, private_key_pem_path=private_key_pem_path
#         )
#         faucet_fund_account(casperlabs_client, key_holder.account_hash_hex)
#         accounts[key_algorithm] = key_holder, private_key_pem_path
#     return accounts


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


def test_transfer(casperlabs_client):
    transfer_amt = 999999
    faucet_fund_account(casperlabs_client, ACCOUNT_ID_HEX)
    deploy_hash = casperlabs_client.transfer(
        TARGET_ACCOUNT, transfer_amt, private_key=ACCOUNT_PRIVATE_PEM_PATH
    )
    result = casperlabs_client.showDeploy(deploy_hash, wait_for_processed=True)
    block_hash = result.processing_results[0].block_info.summary.block_hash
    result = casperlabs_client.balance(TARGET_ACCOUNT, block_hash.hex())
    assert result == transfer_amt


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
