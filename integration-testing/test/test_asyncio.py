import pytest

from casperlabs_local_net.common import Contract
from casperlabs_client.casperlabs_client_aio import CasperLabsClientAIO
from casperlabs_local_net.casperlabs_accounts import Account, GENESIS_ACCOUNT

PAYMENT_AMOUNT = 10 ** 7


@pytest.fixture
def node(one_node_network_with_auto_propose):
    return one_node_network_with_auto_propose.docker_nodes[0]


@pytest.fixture
def client(node):
    return CasperLabsClientAIO(
        node.node_host,
        node.grpc_external_docker_port,
        # node.config.tls_certificate_local_path(),
        # node.config.tls_key_local_path()
    )


@pytest.mark.asyncio
async def test_show_blocks(client):
    result = await client.show_blocks()
    assert len(list(result))


@pytest.mark.asyncio
async def test_deploy_show_block_and_show_deploys(node, client):
    deploy_hash = await client.deploy(
        session=node.resources_folder / Contract.COUNTER_DEFINE,
        from_addr=GENESIS_ACCOUNT.public_key_hex,
        public_key=GENESIS_ACCOUNT.public_key_path,
        private_key=GENESIS_ACCOUNT.private_key_path,
        payment_amount=PAYMENT_AMOUNT,
    )
    deploy_info = await client.wait_for_deploy_processed(deploy_hash)
    processing_result = deploy_info.processing_results[0]
    assert not processing_result.is_error
    assert processing_result.cost > 0
    block_hash = processing_result.block_info.summary.block_hash.hex()

    block_info = await client.show_block(block_hash)
    assert block_info.summary.block_hash.hex() == block_hash

    processing_results = await client.show_deploys(block_hash)
    assert deploy_hash in [p.deploy.deploy_hash.hex() for p in processing_results]


@pytest.mark.asyncio
async def test_transfer_and_balance(node, client):
    test_account = Account(1)

    block_infos = await client.show_blocks()
    block_hash = block_infos[0].summary.block_hash.hex()
    genesis_account_balance = await client.balance(
        GENESIS_ACCOUNT.public_key_hex, block_hash
    )
    assert genesis_account_balance > 0

    transfer_amount = 10 ** 7
    deploy_hash = await client.transfer(
        test_account.public_key_hex,
        transfer_amount,
        from_addr=GENESIS_ACCOUNT.public_key_hex,
        public_key=GENESIS_ACCOUNT.public_key_path,
        private_key=GENESIS_ACCOUNT.private_key_path,
        payment_amount=PAYMENT_AMOUNT,
    )
    deploy_info = await client.wait_for_deploy_processed(deploy_hash)
    block_hash = deploy_info.processing_results[0].block_info.summary.block_hash.hex()
    test_account_balance = await client.balance(test_account.public_key_hex, block_hash)
    assert test_account_balance == transfer_amount
