import pytest

from casperlabs_client import ABI
from test.cl_node.casperlabs_accounts import Account
from test.cl_node.casperlabs_network import TrillionPaymentNodeNetwork
from test.cl_node.nonce_registry import NonceRegistry
from test.cl_node.common import MAX_PAYMENT_ABI, PAYMENT_CONTRACT, HELLO_NAME_CONTRACT

"""
Accounts have two threshold values:
    key_management_threshold
    deploy_threshold

Both are initialized at 1.
"""

ADD_KEY_CONTRACT = "add_associated_key.wasm"  # ABI: Account - Weight
REMOVE_KEY_CONTRACT = "remove_associated_key.wasm"  # ABI: Account
UPDATE_KEY_CONTRACT = "update_associated_key.wasm"  # ABI: Account - Weight
SET_THRESHOLDS_CONTRACT = "set_key_thresholds.wasm"  # ABI: KeyWeight - DeployWeight

IDENTITY_KEY = Account(1)  # 9d39
DEPLOY_KEY = Account(2)  # 4e74
DEPLOY_KEY_WEIGHT = 10
KEY_MGMT_KEY = Account(3)  # 58f7
KEY_MGMT_KEY_WEIGHT = 20
HIGH_WEIGHT_KEY = Account(4)  # 1ca8
HIGH_WEIGHT_KEY_WEIGHT = 200
INITIAL_ACCOUNT_VALUE = 1000000000


def _add_update_associate_key(
    node, weight_key: Account, key: Account, weight: int, contract: str
):
    """ Handles both add and update calls due to commonality """
    session_args = ABI.args(
        [ABI.account(bytes.fromhex(key.public_key_hex)), ABI.u32(weight)]
    )
    return node.deploy_and_propose(
        from_address=IDENTITY_KEY.public_key_hex,
        payment_contract=PAYMENT_CONTRACT,
        session_contract=contract,
        public_key=weight_key.public_key_path,
        private_key=weight_key.private_key_path,
        session_args=session_args,
        payment_args=MAX_PAYMENT_ABI,
    )


def add_associated_key(node, weight_key: Account, key: Account, weight: int):
    """ Associates a key to the IDENTITY_KEY account """
    return _add_update_associate_key(node, weight_key, key, weight, ADD_KEY_CONTRACT)


def update_associated_key(node, weight_key: Account, key: Account, weight: int):
    """ Updates weight of a key for the IDENTITY_KEY account """
    return _add_update_associate_key(node, weight_key, key, weight, UPDATE_KEY_CONTRACT)


def remove_associated_key(node, weight_key: Account, key: Account):
    """ Removes a key from the IDENTITY_KEY account """
    args = ABI.args([ABI.account(bytes.fromhex(key.public_key_hex))])
    return node.deploy_and_propose(
        from_address=IDENTITY_KEY.public_key_hex,
        payment_contract=PAYMENT_CONTRACT,
        session_contract=REMOVE_KEY_CONTRACT,
        public_key=weight_key.public_key_path,
        private_key=weight_key.private_key_path,
        session_args=args,
        payment_args=MAX_PAYMENT_ABI,
    )


def set_key_thresholds(node, weight_key, key_mgmt_weight: int, deploy_weight: int):
    """ Sets key management and deploy thresholds for IDENTITY_KEY account """
    args = ABI.args([ABI.u32(key_mgmt_weight), ABI.u32(deploy_weight)])
    return node.deploy_and_propose(
        from_address=IDENTITY_KEY.public_key_hex,
        payment_contract=PAYMENT_CONTRACT,
        session_contract=SET_THRESHOLDS_CONTRACT,
        public_key=weight_key.public_key_path,
        private_key=weight_key.private_key_path,
        session_args=args,
        payment_args=MAX_PAYMENT_ABI,
    )


def hello_name_deploy(node, weight_key: Account) -> str:
    """ Simple deploy to test deploy permissions """
    return node.deploy_and_propose(
        from_address=IDENTITY_KEY.public_key_hex,
        payment_contract=PAYMENT_CONTRACT,
        session_contract=HELLO_NAME_CONTRACT,
        public_key=weight_key.public_key_path,
        private_key=weight_key.private_key_path,
        session_args=None,
        payment_args=MAX_PAYMENT_ABI,
    )


def assert_deploy_is_not_error(node, block_hash):
    for deploy in node.client.show_deploys(block_hash):
        assert not deploy.is_error, deploy.error_message


def assert_deploy_is_error(node, block_hash: str, error_message: str = None):
    for deploy in node.client.show_deploys(block_hash):
        assert deploy.is_error
        if error_message:
            assert deploy.error_message == error_message


#
# Using module scope to allow breakup of tests into multiple methods without overhead
# All test have effects and should leave account weights and association correct at end
#
@pytest.fixture(scope="module")
def account_setup(docker_client_fixture):
    with TrillionPaymentNodeNetwork(docker_client_fixture) as onn:
        onn.create_cl_network()
        node = onn.docker_nodes[0]
        node.use_python_client()

        node.transfer_to_account(IDENTITY_KEY.file_id, INITIAL_ACCOUNT_VALUE)

        # Create deploy_acct key with weight of 10
        block_hash = add_associated_key(
            node, weight_key=IDENTITY_KEY, key=DEPLOY_KEY, weight=DEPLOY_KEY_WEIGHT
        )
        assert_deploy_is_not_error(node, block_hash)

        # Create key_mgmt key with weight of 20
        block_hash = add_associated_key(
            node, weight_key=IDENTITY_KEY, key=KEY_MGMT_KEY, weight=KEY_MGMT_KEY_WEIGHT
        )
        assert_deploy_is_not_error(node, block_hash)

        # Create high weight key for updating once we exceed weights of others
        block_hash = add_associated_key(
            node,
            weight_key=KEY_MGMT_KEY,
            key=HIGH_WEIGHT_KEY,
            weight=HIGH_WEIGHT_KEY_WEIGHT,
        )
        assert_deploy_is_not_error(node, block_hash)

        # Removing identity key from associated keys
        # System should function with key only as address
        block_hash = remove_associated_key(
            node, weight_key=KEY_MGMT_KEY, key=IDENTITY_KEY
        )
        assert_deploy_is_not_error(node, block_hash)
        yield onn, node


def test_deploy_threshold_cannot_exceed_key_management_threshold(account_setup):
    onn, node = account_setup

    # Attempt to set deploy threshold higher than key management threshold
    block_hash = set_key_thresholds(
        node, weight_key=KEY_MGMT_KEY, key_mgmt_weight=10, deploy_weight=11
    )

    # If set for deploy fails, contract will revert(200)
    assert_deploy_is_error(node, block_hash, "Exit code: 200")


def test_key_cannot_deploy_with_weight_below_threshold(account_setup):
    onn, node = account_setup

    # Set deploy threshold one over key weight
    block_hash = set_key_thresholds(
        node,
        weight_key=KEY_MGMT_KEY,
        key_mgmt_weight=KEY_MGMT_KEY_WEIGHT,
        deploy_weight=DEPLOY_KEY_WEIGHT + 1,
    )
    assert_deploy_is_not_error(node, block_hash)

    # Assert deploy fails
    with pytest.raises(Exception):
        _ = hello_name_deploy(node, DEPLOY_KEY)

    # Reset Nonce consumed calling error
    NonceRegistry.revert(IDENTITY_KEY.public_key_hex)


def test_key_can_deploy_with_weight_at_and_above_threshold(account_setup):
    onn, node = account_setup

    def test_at_weight(set_weight: int):
        # Set threshold
        block_hash = set_key_thresholds(
            node,
            weight_key=KEY_MGMT_KEY,
            key_mgmt_weight=KEY_MGMT_KEY_WEIGHT,
            deploy_weight=set_weight,
        )
        assert_deploy_is_not_error(node, block_hash)

        # Test deploy
        block_hash = hello_name_deploy(node, weight_key=DEPLOY_KEY)
        assert_deploy_is_not_error(node, block_hash)

    test_at_weight(DEPLOY_KEY_WEIGHT - 1)
    test_at_weight(DEPLOY_KEY_WEIGHT)


def test_key_cannot_manage_with_weight_below_threshold(account_setup):
    onn, node = account_setup

    # Set management threshold one over key weight
    block_hash = set_key_thresholds(
        node, KEY_MGMT_KEY, KEY_MGMT_KEY_WEIGHT + 1, DEPLOY_KEY_WEIGHT
    )
    assert_deploy_is_not_error(node, block_hash)

    # Set thresholds should fail
    block_hash = set_key_thresholds(
        node, KEY_MGMT_KEY, KEY_MGMT_KEY_WEIGHT, DEPLOY_KEY_WEIGHT
    )
    # First process of contract fails with a revert(100)
    assert_deploy_is_error(node, block_hash, "Exit code: 100")

    # Remove key should fail
    block_hash = remove_associated_key(node, KEY_MGMT_KEY, DEPLOY_KEY)
    assert_deploy_is_error(node, block_hash, "Exit code: 1")

    # Add key should fail
    block_hash = add_associated_key(node, KEY_MGMT_KEY, IDENTITY_KEY, 10)
    assert_deploy_is_error(node, block_hash, "Exit code: 100")

    # Update key should fail
    block_hash = update_associated_key(
        node, weight_key=KEY_MGMT_KEY, key=DEPLOY_KEY, weight=11
    )
    assert_deploy_is_error(node, block_hash, "Exit code: 100")

    # Reset thresholds
    block_hash = set_key_thresholds(
        node,
        weight_key=HIGH_WEIGHT_KEY,
        key_mgmt_weight=KEY_MGMT_KEY_WEIGHT,
        deploy_weight=DEPLOY_KEY_WEIGHT,
    )
    assert_deploy_is_not_error(node, block_hash)


def test_key_can_manage_at_and_above_threshold(account_setup):
    onn, node = account_setup

    def test_at_weight(set_weight: int):
        # Setup threshold below weight
        block_hash = set_key_thresholds(
            node,
            weight_key=KEY_MGMT_KEY,
            key_mgmt_weight=set_weight,
            deploy_weight=DEPLOY_KEY_WEIGHT,
        )
        assert_deploy_is_not_error(node, block_hash)

        # Test add
        block_hash = add_associated_key(
            node, weight_key=KEY_MGMT_KEY, key=IDENTITY_KEY, weight=1
        )
        assert_deploy_is_not_error(node, block_hash)

        # Test update
        block_hash = update_associated_key(
            node, weight_key=KEY_MGMT_KEY, key=IDENTITY_KEY, weight=DEPLOY_KEY_WEIGHT
        )
        assert_deploy_is_not_error(node, block_hash)

        # Test added and updated key
        block_hash = hello_name_deploy(node, IDENTITY_KEY)
        assert_deploy_is_not_error(node, block_hash)

        # Test remove
        block_hash = remove_associated_key(
            node, weight_key=KEY_MGMT_KEY, key=IDENTITY_KEY
        )
        assert_deploy_is_not_error(node, block_hash)

    test_at_weight(KEY_MGMT_KEY_WEIGHT - 1)
    test_at_weight(KEY_MGMT_KEY_WEIGHT)


def test_removed_key_cannot_be_used_for_deploy(account_setup):
    onn, node = account_setup

    # Deploy should fail with a removed key
    with pytest.raises(Exception):
        _ = hello_name_deploy(node, IDENTITY_KEY)

    NonceRegistry.revert(IDENTITY_KEY.public_key_hex)
