import pytest

from test.cl_node.casperlabs_accounts import Account
from test.cl_node.common import HELLO_NAME_CONTRACT, PAYMENT_CONTRACT, MAX_PAYMENT_ABI


def test_non_account_precondition_failure(trillion_payment_node_network):
    node = trillion_payment_node_network.docker_nodes[0]

    # Getting a non-existent account
    non_existent_account = Account(300)

    # Client returns deploy hash, but will not stay in buffer for proposes.
    _, deploy_hash = node.p_client.deploy(
        from_address=non_existent_account.public_key_hex,
        public_key=non_existent_account.public_key_path,
        private_key=non_existent_account.private_key_path,
        session_contract=HELLO_NAME_CONTRACT,
        payment_contract=PAYMENT_CONTRACT,
        payment_args=MAX_PAYMENT_ABI,
    )

    # Will have InternalError as no deploys to propose
    with pytest.raises(Exception) as e:
        _ = node.p_client.propose()

    # Verify reason for propose failure
    assert e.typename == "InternalError"
    assert str(e.value) == "StatusCode.OUT_OF_RANGE: No new deploys."
