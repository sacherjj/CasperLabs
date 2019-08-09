from typing import List
import logging
import pytest
from pytest import raises
from test.cl_node.casperlabs_accounts import Account
from test.cl_node.casperlabs_accounts import GENESIS_ACCOUNT
from test.cl_node.common import extract_block_hash_from_propose_output
from test.cl_node.docker_node import DockerNode
from test.cl_node.errors import NonZeroExitCodeError
from test.cl_node.wait import wait_for_genesis_block
from test import contract_hash


"""
Test account state retrieval with query-state.

Example output of the Scala client:

account {
  public_key: "3030303030303030303030303030303030303030303030303030303030303030"
  nonce: 1
  purse_id {
    uref: "0000000000000000000000000000000000000000000000000000000000000000"
    access_rights: READ_ADD_WRITE
  }
  associated_keys {
    public_key: "3030303030303030303030303030303030303030303030303030303030303030"
    weight: 1
  }
  action_thresholds {
    deployment_threshold: 1
    key_management_threshold: 1
  }
  account_activity {
    key_management_last_used: 0
    deployment_last_used: 0
    inactivity_period_limit: 100
  }
}

"""


def test_account_state(one_node_network):
    node = one_node_network.docker_nodes[0]

    def account_state(block_hash):
        return node.d_client.query_state(
            block_hash=block_hash, key_type="address", key=node.from_address, path=""
        )

    block_hash = node.deploy_and_propose(
        session_contract="test_counterdefine.wasm",
        payment_contract="test_counterdefine.wasm",
    )
    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error

    response = account_state(block_hash)
    assert response.account.nonce == 1, str(response)

    block_hash = node.deploy_and_propose(
        session_contract="test_countercall.wasm",
        payment_contract="test_countercall.wasm",
    )
    response = account_state(block_hash)
    assert response.account.nonce == 2, str(response)


def test_transfer_with_overdraft(one_node_network):
    def account_state(block_hash, account):
        return node.d_client.query_state(
            block_hash=block_hash, key_type="address", key=account, path=""
        )

    acct1 = Account(1)
    acct2 = Account(2)

    node: DockerNode = one_node_network.docker_nodes[0]
    # Transfer 1000000 from genesis... to acct1...
    block_hash = node.transfer_to_account(
        to_account_id=1, amount=1000000, from_account_id="genesis"
    )

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, f"error_message: {deploys[0].error_message}"

    # Response not used, but assures account exist
    _ = account_state(block_hash, acct1.public_key_hex)

    # Should error as account doesn't exist.
    with raises(Exception):
        _ = account_state(block_hash, acct2.public_key_hex)

    # No API currently exists for getting balance to check transfer.
    # Transfer 750000 from acct1... to acct2...
    block_hash = node.transfer_to_account(
        to_account_id=2, amount=750000, from_account_id=1
    )

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, f"error_message: {deploys[0].error_message}"

    # Response not used, but assures account exist
    _ = account_state(block_hash, acct2.public_key_hex)

    # Transfer 750000000000 from acct1... to acct2...
    # Should fail with acct1 overdrawn.   Requires assert in contract to generate is_error.
    with raises(Exception):
        _ = node.transfer_to_account(
            to_account_id=2, amount=750000000000, from_account_id=1
        )


def test_transfer_to_accounts(one_node_network):
    node: DockerNode = one_node_network.docker_nodes[0]
    # Perform multiple transfers with end result of Acct1 = 100, Acct2 = 100, Acct3 = 800
    node.transfer_to_accounts([(1, 1000), (2, 900, 1), (3, 800, 2)])
    with raises(Exception):
        # Acct 1 has not enough funds so it should fail
        node.transfer_to_account(
            to_account_id=4, amount=100000000000, from_account_id=1
        )
    node.transfer_to_account(to_account_id=4, amount=100, from_account_id=2)
    # TODO: Improve checks once balance is easy to read.


def balance(node, account_address, block_hash):
    try:
        return node.client.get_balance(account_address, block_hash)
    except Exception:
        return 0


def test_scala_client_balance(one_node_network):
    node: DockerNode = one_node_network.docker_nodes[0]

    accounts = [Account(i) for i in range(1, 4)]

    block_hash = list(node.p_client.show_blocks(1))[0].summary.block_hash.hex()

    initial = [
        balance(node, account.public_key_hex, block_hash) for account in accounts
    ]

    # Perform multiple transfers with end result of Acct1 = 200, Acct2 = 100, Acct3 = 700
    hashes = node.transfer_to_accounts([(1, 1000), (2, 800, 1), (3, 700, 2)])

    assert (
        node.d_client.get_balance(
            account_address=accounts[0].public_key_hex, block_hash=hashes[-1]
        )
        == initial[0] + 200
    )
    assert (
        node.d_client.get_balance(
            account_address=accounts[1].public_key_hex, block_hash=hashes[-1]
        )
        == initial[1] + 100
    )
    assert (
        node.d_client.get_balance(
            account_address=accounts[2].public_key_hex, block_hash=hashes[-1]
        )
        == initial[2] + 700
    )


ffi_test_contracts = [
    ("getcallerdefine.wasm", "getcallercall.wasm"),
    ("listknownurefsdefine.wasm", "listknownurefscall.wasm"),
]


def deploy_and_propose_expect_no_errors(node, contract):
    client = node.d_client
    block_hash = node.deploy_and_propose(
        session_contract=contract,
        payment_contract=contract,
        from_address=node.genesis_account.public_key_hex,
        public_key=node.genesis_account.public_key_path,
        private_key=node.genesis_account.private_key_path,
    )
    r = client.show_deploys(block_hash)[0]
    assert r.is_error is False, f"error_message: {r.error_message}"


@pytest.mark.parametrize("define_contract, call_contract", ffi_test_contracts)
def test_get_caller(one_node_network, define_contract, call_contract):
    node = one_node_network.docker_nodes[0]
    deploy_and_propose_expect_no_errors(node, define_contract)
    deploy_and_propose_expect_no_errors(node, call_contract)


@pytest.mark.parametrize(
    "wasm", ["test_helloname.wasm", "old_wasm/test_helloname.wasm"]
)
def test_multiple_propose(one_node_network, wasm):
    """
    Feature file: propose.feature
    Scenario: Single node deploy and multiple propose generates an Exception.
    OP-182: First propose should be success, and subsequent propose calls should throw an error/exception.
    """
    node = one_node_network.docker_nodes[0]
    assert "Success" in node.client.deploy(session_contract=wasm, payment_contract=wasm)
    assert "Success" in node.client.propose()
    number_of_blocks = node.client.get_blocks_count(100)

    try:
        _ = node.client.propose()
        assert False, "Second propose must not succeed, should throw"
    except NonZeroExitCodeError as e:
        assert e.exit_code == 1, "Second propose should fail"
    wait_for_genesis_block(node)

    # Number of blocks after second propose should not change
    assert node.client.get_blocks_count(100) == number_of_blocks


# Examples of query-state executed with the Scala client that result in errors:

# CasperLabs/docker $ ./client.sh node-0 propose
# Response: Success! Block 9d38836598... created and added.

# CasperLabs/docker $ ./client.sh node-0 query-state --block-hash '"9d"' --key '"a91208047c"' --path file.xxx --type hash
# NOT_FOUND: Cannot find block matching hash "9d"

# CasperLabs/docker$ ./client.sh node-0 query-state --block-hash 9d --key '"a91208047c"' --path file.xxx --type hash
# INVALID_ARGUMENT: Key of type hash must have exactly 32 bytes, 5 =/= 32 provided.

# CasperLabs/docker$ ./client.sh node-0 query-state --block-hash 9d --key 3030303030303030303030303030303030303030303030303030303030303030 --path file.xxx --type hash
# INVALID_ARGUMENT: Value not found: " Hash([48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48])"


@pytest.fixture(scope="module")
def node(one_node_network):
    return one_node_network.docker_nodes[0]


@pytest.fixture(scope="module")
def client(node):
    return node.d_client


@pytest.fixture(scope="module")
def block_hash(node):
    return node.deploy_and_propose(
        session_contract="test_helloname.wasm", payment_contract="test_helloname.wasm"
    )


block_hash_queries = [
    (
        {
            "block_hash": "9d000000",
            "key": "a91208047c",
            "path": "file.xxx",
            "key_type": "hash",
        },
        "NOT_FOUND: Cannot find block matching",
    ),
    (
        {"key": "a91208047c", "path": "file.xxx", "key_type": "hash"},
        "INVALID_ARGUMENT: Key of type hash must have exactly 32 bytes",
    ),
    ({"path": "file.xxx", "key_type": "hash"}, "INVALID_ARGUMENT: Value not found"),
]


@pytest.mark.parametrize("query, expected", block_hash_queries)
def test_query_state_error(node, client, block_hash, query, expected):
    if "block_hash" not in query:
        query["block_hash"] = block_hash

    if "key" not in query:
        query["key"] = node.from_address

    with pytest.raises(NonZeroExitCodeError) as excinfo:
        _ = client.query_state(**query)
    assert expected in excinfo.value.output


def test_revert_subcall(client, node):
    # This contract calls another contract that calls revert(2)
    block_hash = node.deploy_and_propose(
        session_contract="test_subcall_revert_define.wasm",
        payment_contract="test_subcall_revert_define.wasm",
    )

    r = client.show_deploys(block_hash)[0]
    assert not r.is_error
    assert r.error_message == ""

    deploy_hash = r.deploy.deploy_hash

    r = client.show_deploy(deploy_hash)
    assert r.deploy.deploy_hash == deploy_hash

    # Help me figure out what subcall-revert-test/call/src/lib.rs should look like
    # TODO: function_counter 0 is a bug, to be fixed in EE.
    h = contract_hash(GENESIS_ACCOUNT.public_key_hex, 0, 0)
    logging.info("The expected contract hash is %s (%s)" % (list(h), h.hex()))

    block_hash = node.deploy_and_propose(
        session_contract="test_subcall_revert_call.wasm",
        payment_contract="test_subcall_revert_call.wasm",
    )
    r = client.show_deploys(block_hash)[0]
    assert r.is_error
    assert r.error_message == "Exit code: 2"


def test_revert_direct(client, node):
    # This contract calls revert(1) directly
    block_hash = node.deploy_and_propose(
        session_contract="test_direct_revert_call.wasm",
        payment_contract="test_direct_revert_call.wasm",
    )

    r = client.show_deploys(block_hash)[0]
    assert r.is_error
    assert r.error_message == "Exit code: 1"


def test_deploy_with_valid_signature(one_node_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with valid signature
    """
    node0 = one_node_network.docker_nodes[0]
    client = node0.client
    client.deploy(
        session_contract="test_helloname.wasm", payment_contract="test_helloname.wasm"
    )

    block_hash = extract_block_hash_from_propose_output(client.propose())
    deploys = client.show_deploys(block_hash)
    assert deploys[0].is_error is False


def test_deploy_with_invalid_signature(one_node_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with invalid signature
    """

    node0 = one_node_network.docker_nodes[0]

    with pytest.raises(NonZeroExitCodeError):
        node0.client.deploy(
            session_contract="test_helloname.wasm",
            payment_contract="test_helloname.wasm",
            private_key="validator-0-private-invalid.pem",
            public_key="validator-0-public-invalid.pem",
        )


"""
Feature file: ~/CasperLabs/integration-testing/features/deploy.feature
"""


def deploy_and_propose(node, contract, nonce=None):
    node.client.deploy(
        session_contract=contract, payment_contract=contract, nonce=nonce
    )
    return extract_block_hash_from_propose_output(node.client.propose())


def deploy(node, contract, nonce):
    message = node.client.deploy(
        session_contract=contract, payment_contract=contract, nonce=nonce
    )
    assert "Success!" in message
    return message.split()[2]


def propose(node):
    return extract_block_hash_from_propose_output(node.client.propose())


def deploy_hashes(node, block_hash):
    return set(d.deploy.deploy_hash for d in node.client.show_deploys(block_hash))


@pytest.mark.parametrize("contract", ["test_helloname.wasm"])
def test_deploy_without_nonce(node, contract: str):
    """
    Feature file: deploy.feature
    Scenario: Deploy without nonce
    """
    with pytest.raises(NonZeroExitCodeError):
        deploy_and_propose(node, contract, "")


@pytest.mark.parametrize(
    "contracts",
    [["test_helloname.wasm", "test_helloworld.wasm", "test_counterdefine.wasm"]],
)
def test_deploy_with_lower_nonce(node, contracts: List[str]):
    """
    Feature file: deploy.feature
    Scenario: Deploy with lower nonce
    """
    for contract in contracts:
        deploy_and_propose(node, contract)

    with pytest.raises(NonZeroExitCodeError):
        deploy_and_propose(node, contract, 2)


@pytest.mark.parametrize(
    "contracts",
    [["test_helloname.wasm", "test_helloworld.wasm", "test_counterdefine.wasm"]],
)
def test_deploy_with_higher_nonce(node, contracts: List[str]):
    """
    Feature file: deploy.feature

    Scenario: Deploy with higher nonce
    """
    # Deploy successfully with nonce 1 => Nonce is 1 for account.
    deploy_and_propose(node, contracts[0], 1)

    deploy_hash = deploy(node, contracts[2], 3)

    with pytest.raises(NonZeroExitCodeError):
        node.client.propose()

    deploy_and_propose(node, contracts[1], 2)

    # The deploy with nonce 3 can be proposed now.
    block_hash = propose(node)
    assert deploy_hash in deploy_hashes(node, block_hash)


@pytest.mark.parametrize(
    "contracts",
    [
        [
            "test_helloname.wasm",
            "test_helloworld.wasm",
            "test_counterdefine.wasm",
            "test_countercall.wasm",
        ]
    ],
)
def test_deploy_with_higher_nonce_does_not_include_previous_deploy(
    node, contracts: List[str]
):
    """
    Feature file: deploy.feature

    Scenario: Deploy with higher nonce and created block does not include previously deployed contract.
    """
    # Deploy successfully with nonce 1 => Nonce is 1 for account.
    deploy_hash = deploy(node, contracts[0], 1)
    block_hash = propose(node)
    assert deploy_hash in deploy_hashes(node, block_hash)

    deploy_hash4 = deploy(node, contracts[1], 4)

    with pytest.raises(NonZeroExitCodeError):
        propose(node)

    deploy_hash2 = deploy(node, contracts[2], 2)
    # The deploy with nonce 4 cannot be proposed now. It will be in the deploy buffer but does not include
    # in the new block created now.
    block_hash = propose(node)
    deploys = deploy_hashes(node, block_hash)
    assert deploy_hash4 not in deploys
    assert deploy_hash2 in deploys

    deploy_hash3 = deploy(node, contracts[3], 3)
    block_hash = propose(node)
    assert deploy_hash3 in deploy_hashes(node, block_hash)

    block_hash = propose(node)
    assert deploy_hash4 in deploy_hashes(node, block_hash)


# Python CLI #

import subprocess
import pytest
import os
from test.cl_node.client_parser import parse_show_blocks, parse_show_deploys, parse


CLI = "casperlabs_client"


class CLIErrorExit(Exception):
    def __init__(self, cp):
        self.cp = cp


@pytest.fixture(scope="module")
def cli(one_node_network):

    node = one_node_network.docker_nodes[0]
    host = os.environ.get("TAG_NAME", None) and node.container_name or "localhost"
    port = node.grpc_external_docker_port

    def invoker(*args):
        command_line = [CLI, "--host", f"{host}", "--port", f"{port}"] + list(args)
        logging.info(f"EXECUTING: {' '.join(command_line)}")
        cp = subprocess.run(
            command_line, stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
        if cp.returncode != 0:
            raise CLIErrorExit(cp)
        return cp.stdout.decode("utf-8")

    return invoker


def test_cli_no_parameters(cli):
    with raises(CLIErrorExit) as ex_info:
        cli()
    assert "You must provide a command" in str(ex_info.value)


def test_cli_help(cli):
    out = cli("--help")
    # deploy,propose,show-block,show-blocks,show-deploy,show-deploys,vdag,query-state
    assert "Casper" in out


def test_cli_show_blocks_and_show_block(cli):
    blocks = parse_show_blocks(cli("show-blocks", "--depth", "1"))
    assert len(blocks) > 0

    for block in blocks:
        block_hash = block.summary.block_hash
        assert len(block_hash) == 32 * 2  # hex

        b = parse(cli("show-block", block_hash))
        assert block_hash == b.summary.block_hash


def test_cli_show_block_not_found(cli):
    block_hash = "00" * 32
    with raises(CLIErrorExit) as ex_info:
        parse(cli("show-block", block_hash))
    # StatusCode.NOT_FOUND: Cannot find block matching hash 0000000000000000000000000000000000000000000000000000000000000000
    assert "NOT_FOUND" in str(ex_info.value)
    assert "Cannot find block matching hash" in str(ex_info.value)


def test_cli_deploy_propose_show_deploys_show_deploy_query_state_and_balance(
    cli, one_node_network
):
    account = one_node_network.docker_nodes[0].test_account
    deploy_response = cli(
        "deploy",
        "--from",
        account.public_key_hex,
        "--nonce",
        "1",
        "--payment",
        "resources/test_helloname.wasm",
        "--session",
        "resources/test_helloname.wasm",
        "--private-key",
        str(account.private_key_path),
        "--public-key",
        str(account.public_key_path),
    )
    # 'Success! Deploy hash: xxxxxxxxx...'
    deploy_hash = deploy_response.split()[3]

    # 'Success! Block hash: xxxxxxxxx...'
    block_hash = cli("propose").split()[3]
    deploys = parse_show_deploys(cli("show-deploys", block_hash))
    deploy_hashes = [d.deploy.deploy_hash for d in deploys]
    assert deploy_hash in deploy_hashes

    deploy_info = parse(cli("show-deploy", deploy_hash))
    assert deploy_info.deploy.deploy_hash == deploy_hash

    result = parse(
        cli(
            "query-state",
            "--block-hash",
            block_hash,
            "--type",
            "address",
            "--key",
            account.public_key_hex,
            "--path",
            "",
        )
    )
    assert "hello_name" in [u.name for u in result.account.known_urefs]

    balance = int(
        cli("balance", "--address", account.public_key_hex, "--block-hash", block_hash)
    )
    assert balance == 1000000
