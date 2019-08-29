import os
import logging
import pytest
import json
from pathlib import Path
from typing import List
from pytest import fixture, raises
from test import contract_hash
from test.cl_node.common import testing_root_path
from test.cl_node.casperlabs_accounts import Account, GENESIS_ACCOUNT
from test.cl_node.common import extract_block_hash_from_propose_output
from test.cl_node.docker_node import DockerNode
from test.cl_node.errors import NonZeroExitCodeError
from test.cl_node.wait import wait_for_genesis_block
from casperlabs_client import ABI
from test.cl_node.cli import CLI, DockerCLI, CLIErrorExit


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


def deploy_and_propose_from_genesis(node, contract):
    return node.deploy_and_propose(
        session_contract=contract,
        payment_contract=contract,
        from_address=GENESIS_ACCOUNT.public_key_hex,
        public_key=GENESIS_ACCOUNT.public_key_path,
        private_key=GENESIS_ACCOUNT.private_key_path,
    )


def account_state(node, block_hash, account=GENESIS_ACCOUNT):
    return node.d_client.query_state(
        block_hash=block_hash, key_type="address", key=account.public_key_hex, path=""
    )


def test_account_state(one_node_network):
    node = one_node_network.docker_nodes[0]

    block_hash = deploy_and_propose_from_genesis(node, "test_counterdefine.wasm")
    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error

    response = account_state(node, block_hash)
    assert response.account.nonce == 1, str(response)

    block_hash = deploy_and_propose_from_genesis(node, "test_countercall.wasm")
    response = account_state(node, block_hash)
    assert response.account.nonce == 2, str(response)


def test_transfer_with_overdraft(one_node_network):

    acct1 = Account(1)
    acct2 = Account(2)

    node: DockerNode = one_node_network.docker_nodes[0]
    # Transfer 1000000 from genesis... to acct1...

    # For compatibility with EE with no execution cost
    # payment_contract="transfer_to_account.wasm"
    block_hash = node.transfer_to_account(
        to_account_id=1,
        amount=1000000,
        from_account_id="genesis",
        payment_contract="transfer_to_account.wasm",
    )

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, f"error_message: {deploys[0].error_message}"

    # Response not used, but assures account exist
    _ = account_state(node, block_hash, acct1)

    # Should error as account doesn't exist.
    with raises(Exception):
        _ = account_state(block_hash, acct2.public_key_hex)

    # No API currently exists for getting balance to check transfer.
    # Transfer 750000 from acct1... to acct2...
    block_hash = node.transfer_to_account(
        to_account_id=2,
        amount=750000,
        from_account_id=1,
        payment_contract="transfer_to_account.wasm",
    )

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, f"error_message: {deploys[0].error_message}"

    # Response not used, but assures account exist
    _ = account_state(node, block_hash, acct2)

    # Transfer 750000000000 from acct1... to acct2...
    # Should fail with acct1 overdrawn.   Requires assert in contract to generate is_error.
    with raises(Exception):
        _ = node.transfer_to_account(
            to_account_id=2,
            amount=750000000000,
            from_account_id=1,
            payment_contract="transfer_to_account.wasm",
        )


def test_transfer_to_accounts(one_node_network):
    node: DockerNode = one_node_network.docker_nodes[0]
    # Perform multiple transfers with end result of Acct1 = 100, Acct2 = 100, Acct3 = 800
    node.transfer_to_accounts([(1, 1000), (2, 900, 1), (3, 800, 2)])
    with raises(Exception):
        # Acct 1 has not enough funds so it should fail
        node.transfer_to_account(
            to_account_id=4,
            amount=100000000000,
            from_account_id=1,
            payment_contract="transfer_to_account.wasm",
        )
    node.transfer_to_account(
        to_account_id=4,
        amount=100,
        from_account_id=2,
        payment_contract="transfer_to_account.wasm",
    )
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
    deploy_and_propose_from_genesis(node, wasm)
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


@pytest.fixture()  # scope="module")
def node(one_node_network):
    return one_node_network.docker_nodes[0]


@pytest.fixture()  # (scope="module")
def client(node):
    return node.d_client


@pytest.fixture()  # (scope="module")
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
        query["key"] = GENESIS_ACCOUNT.public_key_hex

    with pytest.raises(NonZeroExitCodeError) as excinfo:
        _ = client.query_state(**query)
    assert expected in excinfo.value.output


def test_revert_subcall(client, node):
    # This contract calls another contract that calls revert(2)
    block_hash = deploy_and_propose_from_genesis(
        node, "test_subcall_revert_define.wasm"
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

    block_hash = deploy_and_propose_from_genesis(node, "test_subcall_revert_call.wasm")
    r = client.show_deploys(block_hash)[0]
    assert r.is_error
    assert r.error_message == "Exit code: 2"


def test_revert_direct(client, node):
    # This contract calls revert(1) directly
    block_hash = deploy_and_propose_from_genesis(node, "test_direct_revert_call.wasm")

    r = client.show_deploys(block_hash)[0]
    assert r.is_error
    assert r.error_message == "Exit code: 1"


def test_deploy_with_valid_signature(one_node_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with valid signature
    """
    node0 = one_node_network.docker_nodes[0]
    block_hash = deploy_and_propose_from_genesis(node0, "test_helloname.wasm")
    deploys = node0.client.show_deploys(block_hash)
    assert deploys[0].is_error is False


def test_deploy_with_invalid_signature(one_node_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with invalid signature
    """

    node0 = one_node_network.docker_nodes[0]

    with pytest.raises(NonZeroExitCodeError):
        node0.client.deploy(
            from_address=GENESIS_ACCOUNT.public_key_hex,
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
        session_contract=contract,
        payment_contract=contract,
        nonce=nonce,
        from_address=GENESIS_ACCOUNT.public_key_hex,
        public_key=GENESIS_ACCOUNT.public_key_path,
        private_key=GENESIS_ACCOUNT.private_key_path,
    )
    return extract_block_hash_from_propose_output(node.client.propose())


def deploy(node, contract, nonce):
    message = node.client.deploy(
        from_address=GENESIS_ACCOUNT.public_key_hex,
        public_key=GENESIS_ACCOUNT.public_key_path,
        private_key=GENESIS_ACCOUNT.private_key_path,
        session_contract=contract,
        payment_contract=contract,
        nonce=nonce,
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


# Python Client (library)

# fmt: off


def resource(fn):
    cur_path = Path(os.path.realpath(__file__)).parent
    while cur_path.name != "integration-testing":
        cur_path = cur_path.parent
    return cur_path / "resources" / fn


def test_args_parser():
    account_hex = "0001000200030004000500060007000800000001000200030004000500060007"
    account_bytes = (
        b"\x00\x01\x00\x02\x00\x03\x00\x04\x00\x05\x00\x06\x00\x07\x00\x08"
        b"\x00\x00\x00\x01\x00\x02\x00\x03\x00\x04\x00\x05\x00\x06\x00\x07"
    )
    u32 = 1024
    u64 = 1234567890
    json_str = json.dumps([{"u32": u32}, {"account": account_hex}, {"u64": u64}])

    assert ABI.args_from_json(json_str) == ABI.args(
        [ABI.u32(1024), ABI.account(account_bytes), ABI.u64(1234567890)]
    )


@fixture()
def genesis_public_signing_key():
    with GENESIS_ACCOUNT.public_key_binary_file() as f:
        yield f


def test_deploy_with_args(one_node_network, genesis_public_signing_key):
    """
    Deploys test contracts that do:

        revert(get_arg(0)); // for u32 and u512

    and

        revert(sum(address_bytes[u8; 32]) + u32); for multiple argument test.

    Tests args get correctly encoded and decoded in the contract.

    Test expects the test contracts test_args_u32.wasm and test_args_u512.wasm
    to deserialize correctly their arguments and then call revert with value
    of the argument (converted to a Rust native int, as expected by revert).
    If the test contracts don't fail or if their exit code is different
    than expected, the test will fail.
    """
    node = one_node_network.docker_nodes[0]
    client = node.p_client.client

    nonce = 1
    for wasm, encode in [
        (resource("test_args_u32.wasm"), ABI.u32),
        (resource("test_args_u512.wasm"), ABI.u512),
    ]:
        for number in [1, 12, 256, 1024]:
            response, deploy_hash = client.deploy(
                payment=wasm,
                session=wasm,
                public_key=resource("accounts/account-public-genesis.pem"),
                private_key=resource("accounts/account-private-genesis.pem"),
                session_args=ABI.args([encode(number)]),
                nonce=nonce,
            )
            nonce += 1
            logging.info(
                f"DEPLOY RESPONSE: {response} deploy_hash: {deploy_hash.hex()}"
            )

            response = client.propose()
            # Need to convert to hex string from bytes
            block_hash = response.block_hash.hex()

            for deploy_info in client.showDeploys(block_hash):
                assert deploy_info.is_error is True
                assert deploy_info.error_message == f"Exit code: {number}"

            logging.info(f"NONCE ===================== {nonce}")

    wasm = resource("test_args_multi.wasm")
    account_hex = "0101010102020202030303030404040405050505060606060707070708080808"
    number = 1000
    total_sum = sum([1, 2, 3, 4, 5, 6, 7, 8]) * 4 + number

    response, deploy_hash = client.deploy(
        payment=wasm,
        session=wasm,
        public_key=resource("accounts/account-public-genesis.pem"),
        private_key=resource("accounts/account-private-genesis.pem"),
        session_args=ABI.args(
            [ABI.account(bytes.fromhex(account_hex)), ABI.u32(number)]
        ),
        nonce=nonce,
    )
    logging.info(f"DEPLOY RESPONSE: {response} deploy_hash: {deploy_hash.hex()}")
    response = client.propose()

    block_hash = response.block_hash.hex()

    for deploy_info in client.showDeploys(block_hash):
        assert deploy_info.is_error is True
        assert deploy_info.error_message == f"Exit code: {total_sum}"

    for blockInfo in client.showBlocks(10):
        assert blockInfo.status.stats.block_size_bytes > 0


# Python CLI #

@pytest.fixture()  # scope="module")
def cli(one_node_network):
    return CLI(one_node_network.docker_nodes[0], "casperlabs_client")


@pytest.fixture()  # scope="module")
def scala_cli(one_node_network):
    return DockerCLI(one_node_network.docker_nodes[0])


def test_cli_no_parameters(cli):
    with raises(CLIErrorExit) as ex_info:
        cli()
    assert "You must provide a command" in str(ex_info.value)


def test_cli_help(cli):
    out = cli("--help")
    # deploy,propose,show-block,show-blocks,show-deploy,show-deploys,vdag,query-state
    assert "Casper" in out


def test_cli_show_blocks_and_show_block(cli):
    blocks = cli("show-blocks", "--depth", "1")
    assert len(blocks) > 0

    for block in blocks:
        block_hash = block.summary.block_hash
        assert len(block_hash) == 32 * 2  # hex

        b = cli("show-block", block_hash)
        assert block_hash == b.summary.block_hash


def test_cli_show_block_not_found(cli):
    block_hash = "00" * 32
    with raises(CLIErrorExit) as ex_info:
        cli("show-block", block_hash)
    # StatusCode.NOT_FOUND: Cannot find block matching hash 0000000000000000000000000000000000000000000000000000000000000000
    assert "NOT_FOUND" in str(ex_info.value)
    assert "Cannot find block matching hash" in str(ex_info.value)


def account_nonce(account_public_key_base16, node):
    client = node.p_client
    nonces = [
        d.deploy.header.nonce
        for b in client.show_blocks(1000000)
        for d in client.show_deploys(b.summary.block_hash.hex())
        if d.deploy.header.account_public_key.hex() == account_public_key_base16
    ]
    return max(nonces, default=0) + 1


def test_cli_deploy_propose_show_deploys_show_deploy_query_state_and_balance(cli):
    resources_path = testing_root_path() / "resources"

    account = GENESIS_ACCOUNT
    nonce = 1

    deploy_hash = cli(
        "deploy",
        "--from",
        account.public_key_hex,
        "--nonce",
        nonce,
        "--payment",
        str(resources_path / "test_helloname.wasm"),
        "--session",
        str(resources_path / "test_helloname.wasm"),
        "--private-key",
        str(account.private_key_path),
        "--public-key",
        str(account.public_key_path),
    )
    block_hash = cli("propose")
    deploys = cli("show-deploys", block_hash)
    deploy_hashes = [d.deploy.deploy_hash for d in deploys]
    assert deploy_hash in deploy_hashes

    deploy_info = cli("show-deploy", deploy_hash)
    assert deploy_info.deploy.deploy_hash == deploy_hash

    result = cli("query-state",
                 "--block-hash", block_hash,
                 "--type", "address",
                 "--key", account.public_key_hex,
                 "--path", "",)
    assert "hello_name" in [u.name for u in result.account.known_urefs]

    balance = int(
        cli("balance", "--address", account.public_key_hex, "--block-hash", block_hash)
    )
    # assert balance == 1000000 # regular test account
    assert balance == 1000000000  # genesis

# CLI ABI


abi_unsigned_test_data = [
    ("u32", 'test_args_u32.wasm'),
    ("u512", 'test_args_u512.wasm'),
]
@pytest.mark.parametrize("unsigned_type, test_contract", abi_unsigned_test_data)
def test_cli_abi_unsigned(cli, unsigned_type, test_contract):
    account = GENESIS_ACCOUNT
    nonce = 0
    for number in [2, 256, 1024]:
        nonce += 1
        args = json.dumps([{unsigned_type: number}])
        deploy_hash = cli('deploy',
                          '--from', account.public_key_hex,
                          '--nonce', nonce,
                          '--session', resource(test_contract),
                          '--session-args', f"{args}",
                          '--payment', resource(test_contract),
                          '--private-key', account.private_key_path,
                          '--public-key', account.public_key_path,)

        cli('propose')
        deploy_info = cli("show-deploy", deploy_hash)
        assert deploy_info.processing_results[0].is_error is True
        assert deploy_info.processing_results[0].error_message == f"Exit code: {number}"


def test_cli_abi_multiple(cli):
    account = GENESIS_ACCOUNT
    test_contract = resource("test_args_multi.wasm")
    account_hex = "0101010102020202030303030404040405050505060606060707070708080808"
    number = 1000
    total_sum = sum([1, 2, 3, 4, 5, 6, 7, 8]) * 4 + number

    args = json.dumps([{'account': account_hex}, {'u32': number}])
    deploy_hash = cli('deploy',
                      '--nonce', 1,
                      '--from', account.public_key_hex,
                      '--session', resource(test_contract),
                      '--session-args', f"{args}",
                      '--payment', resource(test_contract),
                      '--private-key', account.private_key_path,
                      '--public-key', account.public_key_path,)
    cli('propose')
    deploy_info = cli("show-deploy", deploy_hash)
    assert deploy_info.processing_results[0].is_error is True
    assert deploy_info.processing_results[0].error_message == f"Exit code: {total_sum}"


def test_cli_scala_help(scala_cli):
    output = scala_cli('--help')
    assert 'Subcommand: make-deploy' in output


def test_cli_scala_extended_deploy(scala_cli):
    cli = scala_cli
    account = GENESIS_ACCOUNT

    # TODO: when paralelizing testd, make sure test don't collide
    # when trying to access the same file, perhaps map containers /tmp
    # to a unique hosts's directory.

    test_contract = "/data/test_helloname.wasm"
    cli('make-deploy',
        '--nonce', 1,
        '-o', '/tmp/unsigned.deploy',
        '--from', account.public_key_hex,
        '--session', test_contract,
        '--payment', test_contract)

    cli('sign-deploy',
        '-i', '/tmp/unsigned.deploy',
        '-o', '/tmp/signed.deploy',
        '--private-key', account.private_key_docker_path,
        '--public-key', account.public_key_docker_path)

    deploy_hash = cli('send-deploy', '-i', '/tmp/signed.deploy')
    cli('propose')
    deploy_info = cli("show-deploy", deploy_hash)
    assert not deploy_info.processing_results[0].is_error

    try:
        os.remove('/tmp/unsigned.deploy')
        os.remove('/tmp/signed.deploy')
    except Exception as e:
        logging.warning(f"Could not delete temporary files: {str(e)}")
