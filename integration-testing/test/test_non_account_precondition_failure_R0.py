import pytest

from casperlabs_local_net.casperlabs_accounts import Account
from casperlabs_local_net.common import Contract


def test_non_account_precondition_failure(trillion_payment_node_network):
    node = trillion_payment_node_network.docker_nodes[0]

    # Getting a non-existent account
    non_existent_account = Account(300)

    # Client returns deploy hash, but will not stay in buffer for proposes.
    deploy_hash = node.p_client.deploy(
        from_address=non_existent_account.public_key_hex,
        public_key=non_existent_account.public_key_path,
        private_key=non_existent_account.private_key_path,
        session_contract=Contract.HELLO_NAME_DEFINE,
    )

    # Will have InternalError as no deploys to propose
    with pytest.raises(Exception) as e:
        node.p_client.client.wait_for_deploy_processed(deploy_hash)

    # Verify reason for propose failure
    assert "DISCARDED" in str(e.value)
    assert "Authorization failure: not authorized." in str(e.value)
