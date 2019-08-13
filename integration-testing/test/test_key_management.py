import json
import pytest

from test.cl_node.casperlabs_accounts import Account
from casperlabs_client import ABI
from test.cl_node.nonce_registry import NonceRegistry

# from test.cl_node.nonce_registry import NonceRegistry

"""
Accounts have two threshold values:
    key_management_threshold
    deploy_threshold

Both are initialized at 1.

Test that deploy_threshold cannot be set higher than key_management_threshold

Test key cannot deploy if it has weight less than deploy_threshold

Test key can deploy if it has weight [equal to / greater than] deploy threshold

Test key cannot [add_associated_account / update_associated_account]
    if it has weight less than key_management_threshold

Test key can [add_associated_account / update_associated_account]
    if it has weight [equal to / greater than] key_management_threshold

Test key cannot be used for [deploy / add_associated_account / update_associated_account] after removed.

"""

ADD_KEY_CONTRACT = "add_associated_key.wasm"  # ABI: Account - Weight
REMOVE_KEY_CONTRACT = "remove_associated_key.wasm"  # ABI: Account
UPDATE_KEY_CONTRACT = "update_associated_key.wasm"  # ABI: Account - Weight
SET_THRESHOLDS_CONTRACT = "set_key_thresholds.wasm"  # ABI: KeyWeight - DeployWeight
HELLO_NAME_CONTRACT = "test_helloname.wasm"


def account_weight_abi(key: str, weight: int):
    args_json = json.dumps([{"account": key}, {"u32": weight}])
    return ABI.args_from_json(args_json)


def create_associated_key(node, identity_key: str, weight_key, key: str, weight: int):
    return node.deploy_and_propose(
        from_address=identity_key,
        payment_contract=ADD_KEY_CONTRACT,
        session_contract=ADD_KEY_CONTRACT,
        public_key=weight_key.public_key_path,
        private_key=weight_key.private_key_path,
        args=account_weight_abi(key, weight),
    )


def update_associated_key(node, identity_key: str, weight_key, key: str, weight: int):
    return node.deploy_and_propose(
        from_address=identity_key,
        payment_contract=UPDATE_KEY_CONTRACT,
        session_contract=UPDATE_KEY_CONTRACT,
        public_key=weight_key.public_key_path,
        private_key=weight_key.private_key_path,
        args=account_weight_abi(key, weight),
    )


def remove_associated_key(node, identity_key: str, weight_key, key: str):
    args_json = json.dumps([{"account": key}])
    args = ABI.args_from_json(args_json)
    return node.deploy_and_propose(
        from_address=identity_key,
        payment_contract=REMOVE_KEY_CONTRACT,
        session_contract=REMOVE_KEY_CONTRACT,
        public_key=weight_key.public_key_path,
        private_key=weight_key.private_key_path,
        args=args,
    )


def set_key_thresholds(
    node, identity_key: str, weight_key, key_management_weight: int, deploy_weight: int
):
    args_json = json.dumps([{"u32": key_management_weight}, {"u32": deploy_weight}])
    args = ABI.args_from_json(args_json)
    return node.deploy_and_propose(
        from_address=identity_key,
        payment_contract=SET_THRESHOLDS_CONTRACT,
        session_contract=SET_THRESHOLDS_CONTRACT,
        public_key=weight_key.public_key_path,
        private_key=weight_key.private_key_path,
        args=args,
    )


def assert_deploy_is_not_error(node, block_hash):
    deploys = list(node.client.show_deploys(block_hash))
    assert not deploys[0].is_error, deploys[0].error_message


def assert_deploy_is_error(node, block_hash: str, error_message: str = None):
    deploys = list(node.client.show_deploys(block_hash))
    assert deploys[0].is_error
    if error_message:
        assert deploys[0].error_message == error_message


def test_key_management(one_node_network):
    onn = one_node_network
    node = onn.docker_nodes[0]
    node.use_python_client()

    identity_key = Account(1)  # 9d39
    node.transfer_to_account(1, 1000000)
    deploy_key = Account(2)  # 4e74
    key_mgmt_key = Account(3)  # 58f7
    high_weight_key = Account(4)  #

    # Create deploy_acct key with weight of 10
    block_hash = create_associated_key(
        node,
        identity_key=identity_key.public_key_hex,
        weight_key=identity_key,
        key=deploy_key.public_key_hex,
        weight=10,
    )
    assert_deploy_is_not_error(node, block_hash)

    # Create key_mgmt key with weight of 20
    block_hash = create_associated_key(
        node,
        identity_key=identity_key.public_key_hex,
        weight_key=identity_key,
        key=key_mgmt_key.public_key_hex,
        weight=20,
    )
    assert_deploy_is_not_error(node, block_hash)

    # Create high weight key for updating once we exceed weights of others
    block_hash = create_associated_key(
        node,
        identity_key=identity_key.public_key_hex,
        weight_key=key_mgmt_key,
        key=high_weight_key.public_key_hex,
        weight=255,
    )
    assert_deploy_is_not_error(node, block_hash)

    # Removing identity key from associated keys
    block_hash = remove_associated_key(
        node,
        identity_key=identity_key.public_key_hex,
        weight_key=key_mgmt_key,
        key=identity_key.public_key_hex,
    )

    # Start thresholds under key weights
    set_key_thresholds(
        node,
        identity_key=identity_key.public_key_hex,
        weight_key=key_mgmt_key,
        deploy_weight=9,
        key_management_weight=19,
    )
    assert_deploy_is_not_error(node, block_hash)

    # Remove deploy key
    block_hash = remove_associated_key(
        node,
        key=deploy_key.public_key_hex,
        identity_key=identity_key.public_key_hex,
        weight_key=key_mgmt_key,
    )
    assert_deploy_is_not_error(node, block_hash)

    # Deploy with removed key
    with pytest.raises(Exception):
        block_hash = node.deploy_and_propose(
            from_address=identity_key.public_key_hex,
            payment_contract=HELLO_NAME_CONTRACT,
            session_contract=HELLO_NAME_CONTRACT,
            public_key=deploy_key.public_key_path,
            private_key=deploy_key.private_key_path,
        )

    NonceRegistry.revert(identity_key.public_key_hex)

    # Add deploy_key back
    block_hash = create_associated_key(
        node,
        identity_key=identity_key.public_key_hex,
        weight_key=high_weight_key,
        key=deploy_key.public_key_hex,
        weight=10,
    )
    assert_deploy_is_not_error(node, block_hash)

    # Deploy with weight over threshold
    block_hash = node.deploy_and_propose(
        from_address=identity_key.public_key_hex,
        payment_contract=HELLO_NAME_CONTRACT,
        session_contract=HELLO_NAME_CONTRACT,
        public_key=deploy_key.public_key_path,
        private_key=deploy_key.private_key_path,
    )
    assert_deploy_is_not_error(node, block_hash)

    # Key management weight over threshold
    block_hash = set_key_thresholds(
        node,
        identity_key=identity_key.public_key_hex,
        weight_key=key_mgmt_key,
        deploy_weight=10,
        key_management_weight=20,
    )
    assert_deploy_is_not_error(node, block_hash)

    # Deploy with weight at threshold
    block_hash = node.deploy_and_propose(
        from_address=identity_key.public_key_hex,
        payment_contract=HELLO_NAME_CONTRACT,
        session_contract=HELLO_NAME_CONTRACT,
        public_key=deploy_key.public_key_path,
        private_key=deploy_key.private_key_path,
    )
    assert_deploy_is_not_error(node, block_hash)

    # Key management weight at threshold
    block_hash = set_key_thresholds(
        node,
        identity_key=identity_key.public_key_hex,
        weight_key=key_mgmt_key,
        deploy_weight=11,
        key_management_weight=21,
    )
    assert_deploy_is_not_error(node, block_hash)

    # Deploy with weight under threshold
    block_hash = node.deploy_and_propose(
        from_address=identity_key.public_key_hex,
        payment_contract=HELLO_NAME_CONTRACT,
        session_contract=HELLO_NAME_CONTRACT,
        public_key=deploy_key.public_key_path,
        private_key=deploy_key.private_key_path,
    )
    assert_deploy_is_error(node, block_hash, "DeploymentAuthorizationFailure")

    NonceRegistry.revert(identity_key.public_key_hex)

    # Testing deploy after failure for Nonce issue.
    block_hash = node.deploy_and_propose(
        from_address=identity_key.public_key_hex,
        payment_contract=HELLO_NAME_CONTRACT,
        session_contract=HELLO_NAME_CONTRACT,
        public_key=high_weight_key.public_key_path,
        private_key=high_weight_key.private_key_path,
    )
    assert_deploy_is_not_error(node, block_hash)

    # NonceRegistry.revert(identity_key.public_key_hex)

    # CURRENTLY NO WORKING WITH EE-562
    # Key management weight under threshold
    # block_hash = set_key_thresholds(
    #     node,
    #     identity_key=identity_key.public_key_hex,
    #     weight_key=key_mgmt_key,
    #     deploy_weight=10,
    #     key_management_weight=21,
    # )
    # assert_deploy_is_error(node, block_hash)
