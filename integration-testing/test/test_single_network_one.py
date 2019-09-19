import os
import logging
import pytest
import json
from pytest import fixture, raises

from casperlabs_local_net.contract_address import contract_address
from casperlabs_local_net.casperlabs_accounts import Account, GENESIS_ACCOUNT
from casperlabs_local_net.common import (
    resources_path,
    extract_block_hash_from_propose_output,
    Contract,
    MAX_PAYMENT_ABI,
)
from casperlabs_local_net.docker_node import DockerNode
from casperlabs_local_net.errors import NonZeroExitCodeError
from casperlabs_local_net.wait import wait_for_genesis_block
from casperlabs_client import ABI
from casperlabs_local_net.cli import CLI, DockerCLI, CLIErrorExit

"""
Test account state retrieval with query-state.

Example output of the Scala client:

account {
  public_key: "3030303030303030303030303030303030303030303030303030303030303030"
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
    return node.p_client.deploy_and_propose(
        session_contract=contract,
        from_address=GENESIS_ACCOUNT.public_key_hex,
        public_key=GENESIS_ACCOUNT.public_key_path,
        private_key=GENESIS_ACCOUNT.private_key_path,
    )


def account_state(node, block_hash, account=GENESIS_ACCOUNT):
    return node.d_client.query_state(
        block_hash=block_hash, key_type="address", key=account.public_key_hex, path=""
    )


def test_account_state(node):
    block_hash = deploy_and_propose_from_genesis(node, Contract.COUNTERDEFINE)
    deploys = node.d_client.show_deploys(block_hash)
    assert not deploys[0].is_error

    acct_state = account_state(node, block_hash)
    known_urefs = acct_state.account[0].known_urefs
    names = [uref.name for uref in known_urefs]
    assert "counter" in names

    block_hash = deploy_and_propose_from_genesis(node, Contract.COUNTERCALL)
    acct_state = account_state(node, block_hash)
    known_urefs = acct_state.account[0].known_urefs
    names = [uref.name for uref in known_urefs]
    assert "counter" in names


def test_transfer_with_overdraft(node):
    # Notated uses of account ids in common.py
    a_id = 297
    b_id = 296

    acct_a = Account(a_id)
    acct_b = Account(b_id)

    initial_amt = 100000000
    block_hash = node.transfer_to_account(to_account_id=a_id, amount=initial_amt)

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, f"error_message: {deploys[0].error_message}"

    # Response not used, but assures account exist
    _ = account_state(node, block_hash, acct_a)

    # Should error as account doesn't exist.
    with raises(Exception):
        _ = account_state(block_hash, acct_b.public_key_hex)

    # No API currently exists for getting balance to check transfer.
    # Transfer 750000 from acct1... to acct2...
    block_hash = node.transfer_to_account(
        to_account_id=b_id, amount=750, from_account_id=a_id
    )

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, f"error_message: {deploys[0].error_message}"

    # Response not used, but assures account exist
    _ = account_state(node, block_hash, acct_b)

    # Should fail with acct_a overdrawn.   Requires assert in contract to generate is_error.
    with raises(Exception):
        _ = node.transfer_to_account(
            to_account_id=b_id, amount=initial_amt * 10, from_account_id=a_id
        )


def test_transfer_to_accounts(node):
    # Notated uses of account ids in common.py
    a_id = 300
    b_id = 299
    c_id = 298

    initial_amt = 100000000
    acct_a = Account(a_id)
    acct_b = Account(b_id)
    acct_c = Account(c_id)

    # Setup accounts with enough to transfer and pay for transfer
    node.transfer_to_accounts([(a_id, initial_amt), (b_id, initial_amt)])

    with raises(Exception):
        # Acct a has not enough funds so it should fail
        node.transfer_to_account(
            to_account_id=c_id, amount=initial_amt * 10, from_account_id=a_id
        )

    # This is throwing an Exit 1.  (Transfer Failure in Contract)
    node.transfer_to_account(to_account_id=c_id, amount=700, from_account_id=b_id)

    blocks = node.p_client.show_blocks(10)
    block = blocks.__next__()
    block_hash = block.summary.block_hash.hex()

    acct_a_bal = node.d_client.get_balance(acct_a.public_key_hex, block_hash)
    assert (
        acct_a_bal < initial_amt
    ), "Should not have transferred any money, but spent on payment"

    acct_b_bal = node.d_client.get_balance(acct_b.public_key_hex, block_hash)
    assert (
        acct_b_bal < initial_amt - 700
    ), "Should be transfer_amt - 700 - payment for transfer"

    acct_c_bal = node.d_client.get_balance(acct_c.public_key_hex, block_hash)
    assert acct_c_bal == 700, "Should be result of only transfers in"


def balance(node, account_address, block_hash):
    try:
        return node.d_client.get_balance(account_address, block_hash)
    except Exception:
        return 0


def test_scala_client_balance(one_node_network):
    node: DockerNode = one_node_network.docker_nodes[0]

    accounts = [Account(i) for i in range(1, 3)]

    block_hash = list(node.p_client.show_blocks(1))[0].summary.block_hash.hex()

    initial_bal = {
        account.file_id: balance(node, account.public_key_hex, block_hash)
        for account in accounts
    }

    transfer_amt = {1: 100, 2: 800}

    # All have to come from genesis to have enough to pay for transaction
    hashes = node.transfer_to_accounts([(1, transfer_amt[1]), (2, transfer_amt[2])])

    current_bal = {
        account.file_id: balance(node, account.public_key_hex, hashes[-1])
        for account in accounts
    }

    for file_id in (1, 2):
        assert current_bal[file_id] == initial_bal[file_id] + transfer_amt[file_id]


ffi_test_contracts = [
    ("getcallerdefine.wasm", "getcallercall.wasm"),
    ("listknownurefsdefine.wasm", "listknownurefscall.wasm"),
]


def deploy_and_propose_expect_no_errors(node, contract):
    block_hash = node.p_client.deploy_and_propose(
        session_contract=contract,
        from_address=node.genesis_account.public_key_hex,
        public_key=node.genesis_account.public_key_path,
        private_key=node.genesis_account.private_key_path,
    )
    r = node.p_client.show_deploys(block_hash).__next__()
    assert r.is_error is False, f"error_message: {r.error_message}"


@pytest.mark.parametrize("define_contract, call_contract", ffi_test_contracts)
def test_get_caller(one_node_network, define_contract, call_contract):
    node = one_node_network.docker_nodes[0]
    deploy_and_propose_expect_no_errors(node, define_contract)
    deploy_and_propose_expect_no_errors(node, call_contract)


@pytest.mark.parametrize("wasm", [Contract.HELLONAME, "old_wasm/test_helloname.wasm"])
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
    return node.d_client.deploy_and_propose(session_contract="test_helloname.wasm")


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

    block_hash = deploy_and_propose_from_genesis(node, "test_subcall_revert_call.wasm")
    r = client.show_deploys(block_hash)[0]
    assert r.is_error
    assert r.error_message == "Exit code: 2"


def test_revert_direct(client, node):
    # This contract calls revert(1) directly
    block_hash = deploy_and_propose_from_genesis(node, Contract.DIRECT_REVERT_CALL)

    r = client.show_deploys(block_hash)[0]
    assert r.is_error
    assert r.error_message == "Exit code: 1"


def test_deploy_with_valid_signature(one_node_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with valid signature
    """
    node0 = one_node_network.docker_nodes[0]
    block_hash = deploy_and_propose_from_genesis(node0, Contract.HELLONAME)
    deploys = node0.client.show_deploys(block_hash)
    assert deploys[0].is_error is False


def test_deploy_with_invalid_signature(one_node_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with invalid signature
    """

    node0 = one_node_network.docker_nodes[0]

    with pytest.raises(NonZeroExitCodeError):
        node0.d_client.deploy(
            from_address=GENESIS_ACCOUNT.public_key_hex,
            session_contract=Contract.HELLONAME,
            private_key="validator-0-private-invalid.pem",
            public_key="validator-0-public-invalid.pem",
        )


"""
Feature file: ~/CasperLabs/integration-testing/features/deploy.feature
"""


def deploy_and_propose(node, contract):
    node.p_client.deploy(
        session_contract=contract,
        from_address=GENESIS_ACCOUNT.public_key_hex,
        public_key=GENESIS_ACCOUNT.public_key_path,
        private_key=GENESIS_ACCOUNT.private_key_path,
    )
    return extract_block_hash_from_propose_output(node.client.propose())


def deploy(node, contract):
    message = node.p_client.deploy(
        from_address=GENESIS_ACCOUNT.public_key_hex,
        public_key=GENESIS_ACCOUNT.public_key_path,
        private_key=GENESIS_ACCOUNT.private_key_path,
        session_contract=contract,
    )
    assert "Success!" in message
    return message.split()[2]


def propose(node):
    return extract_block_hash_from_propose_output(node.d_client.propose())


def deploy_hashes(node, block_hash):
    return set(d.deploy.deploy_hash for d in node.client.show_deploys(block_hash))


# Python Client (library)

# fmt: off


def test_args_parser():
    account = (
        b"\x00\x01\x00\x02\x00\x03\x00\x04\x00\x05\x00\x06\x00\x07\x00\x08"
        b"\x00\x00\x00\x01\x00\x02\x00\x03\x00\x04\x00\x05\x00\x06\x00\x07"
    )

    amount = 123456

    args = [{"name": "amount", "value": {"long_value": amount}},
            {"name": "account", "value": {"bytes_value": account.hex()}},
            {"name": "purse_id", "value": {"optional_value": {}}},
            {"name": "number", "value": {"big_int": {"value": "2", "bit_width": 512}}}]

    json_str = json.dumps(args)

    assert ABI.args_from_json(json_str) == ABI.args(
        [ABI.long_value(amount), ABI.account(account), ABI.optional_value(None), ABI.big_int(2)]
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

    for wasm, encode in [
        (resources_path() / Contract.ARGS_U32, ABI.u32),
        (resources_path() / Contract.ARGS_U512, ABI.u512),
    ]:
        for number in [1, 12, 256, 1024]:
            response, deploy_hash = client.deploy(
                session=wasm,
                session_args=ABI.args([encode(number)]),
                payment=resources_path() / Contract.STANDARD_PAYMENT,
                payment_args=MAX_PAYMENT_ABI,
                public_key=GENESIS_ACCOUNT.public_key_path,
                private_key=GENESIS_ACCOUNT.private_key_path,
            )
            logging.info(
                f"DEPLOY RESPONSE: {response} deploy_hash: {deploy_hash.hex()}"
            )

            response = client.propose()
            # Need to convert to hex string from bytes
            block_hash = response.block_hash.hex()

            for deploy_info in client.showDeploys(block_hash):
                assert deploy_info.is_error is True
                assert deploy_info.error_message == f"Exit code: {number}"

    wasm = resources_path() / Contract.ARGS_MULTI
    account_hex = "0101010102020202030303030404040405050505060606060707070708080808"
    number = 1000
    total_sum = sum([1, 2, 3, 4, 5, 6, 7, 8]) * 4 + number

    response, deploy_hash = client.deploy(
        session=wasm,
        session_args=ABI.args(
            [ABI.account(bytes.fromhex(account_hex)), ABI.u32(number)]
        ),
        payment=resources_path() / Contract.STANDARD_PAYMENT,
        payment_args=MAX_PAYMENT_ABI,
        public_key=GENESIS_ACCOUNT.public_key_path,
        private_key=GENESIS_ACCOUNT.private_key_path,
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
    # StatusCode.NOT_FOUND: Cannot find block matching hash
    # 0000000000000000000000000000000000000000000000000000000000000000
    assert "NOT_FOUND" in str(ex_info.value)
    assert "Cannot find block matching hash" in str(ex_info.value)


def test_cli_deploy_propose_show_deploys_show_deploy_query_state_and_balance(cli):
    account = cli.node.test_account

    deploy_hash = cli(
        "deploy",
        "--from", account.public_key_hex,
        "--payment", cli.resource(Contract.STANDARD_PAYMENT),
        "--session", cli.resource(Contract.HELLONAME),
        "--private-key", cli.private_key_path(account),
        "--public-key", cli.public_key_path(account),
        "--payment-args", cli.payment_json
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
                 "--path", "", )
    assert "hello_name" in [u.name for u in result.account.known_urefs]

    balance = int(
        cli("balance", "--address", account.public_key_hex, "--block-hash", block_hash)
    )
    # TODO Need constant for where this 1000000000 is from.
    assert balance < 1000000000  # genesis minus payment


# CLI ABI


def int_value(x):
    return x


def big_int_value(x):
    return {'value': str(x), 'bit_width': 512}


abi_unsigned_test_data = [
    ("int_value", 'test_args_u32.wasm', int_value),
    ("big_int", 'test_args_u512.wasm', big_int_value),
]


@pytest.mark.parametrize("unsigned_type, test_contract, value", abi_unsigned_test_data)
def test_cli_abi_unsigned_scala(node, unsigned_type, test_contract, value):
    check_cli_abi_unsigned(DockerCLI(node),
                           unsigned_type,
                           value,
                           test_contract)


@pytest.mark.parametrize("unsigned_type, test_contract, value", abi_unsigned_test_data)
def test_cli_abi_unsigned_python(node, unsigned_type, test_contract, value):
    check_cli_abi_unsigned(CLI(node),
                           unsigned_type,
                           value,
                           test_contract)


def check_cli_abi_unsigned(cli, unsigned_type, value, test_contract):
    account = GENESIS_ACCOUNT
    for number in [2, 256, 1024]:
        session_args = json.dumps([{"name": "number", "value": {unsigned_type: value(number)}}])
        args = ('deploy',
                '--from', account.public_key_hex,
                '--session', cli.resource(test_contract),
                '--session-args', cli.format_json_str(session_args),
                '--payment', cli.resource(Contract.STANDARD_PAYMENT),
                '--payment-args', cli.payment_json,
                '--private-key', cli.private_key_path(account),
                '--public-key', cli.public_key_path(account))
        logging.info(f"EXECUTING {' '.join(cli.expand_args(args))}")
        deploy_hash = cli(*args)

        cli('propose')
        deploy_info = cli("show-deploy", deploy_hash)
        assert deploy_info.processing_results[0].is_error is True
        assert deploy_info.processing_results[0].error_message == f"Exit code: {number}"


def test_cli_abi_multiple(cli):
    account = GENESIS_ACCOUNT
    account_hex = "0101010102020202030303030404040405050505060606060707070708080808"
    number = 1000
    total_sum = sum([1, 2, 3, 4, 5, 6, 7, 8]) * 4 + number

    session_args = json.dumps([{'name': 'account', 'value': {'account': account_hex}},
                               {'name': 'number', 'value': {'int_value': number}}])
    deploy_hash = cli('deploy',
                      '--from', account.public_key_hex,
                      '--session', cli.resource(Contract.ARGS_MULTI),
                      '--session-args', session_args,
                      '--private-key', cli.private_key_path(account),
                      '--public-key', cli.public_key_path(account),
                      '--payment', cli.resource(Contract.STANDARD_PAYMENT),
                      '--payment-args', cli.payment_json)
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

    # TODO: when paralelizing tests, make sure test don't collide
    # when trying to access the same file, perhaps map containers /tmp
    # to a unique hosts's directory.

    cli('make-deploy',
        '-o', '/tmp/unsigned.deploy',
        '--from', account.public_key_hex,
        '--session', cli.resource(Contract.HELLONAME),
        '--payment', cli.resource(Contract.STANDARD_PAYMENT),
        "--payment-args", cli.payment_json)

    cli('sign-deploy',
        '-i', '/tmp/unsigned.deploy',
        '-o', '/tmp/signed.deploy',
        '--private-key', account.private_key_docker_path,
        '--public-key', account.public_key_docker_path)

    deploy_hash = cli('send-deploy', '-i', '/tmp/signed.deploy')
    cli('propose')
    deploy_info = cli("show-deploy", deploy_hash)
    assert not deploy_info.processing_results[0].is_error

    # TODO: This never gets cleaned up if assert fails.
    try:
        os.remove('/tmp/unsigned.deploy')
        os.remove('/tmp/signed.deploy')
    except Exception as e:
        logging.warning(f"Could not delete temporary files: {str(e)}")


def test_cli_scala_direct_call_by_hash_and_name(scala_cli):
    check_cli_direct_call_by_hash_and_name(scala_cli, scala_cli)


def test_cli_python_direct_call_by_hash_and_name(cli, scala_cli):
    check_cli_direct_call_by_hash_and_name(cli, scala_cli)


def check_cli_direct_call_by_hash_and_name(cli, scala_cli):
    # TODO: For now using scala_cli for assertions because for some
    # strange reason Python CLI doesn't show is_error and error_message
    # in the output of show-deploys. This has to be fixed asap.
    account = cli.node.test_account
    cli.set_default_deploy_args('--from', account.public_key_hex,
                                '--private-key', cli.private_key_path(account),
                                '--public-key', cli.public_key_path(account),
                                '--payment', cli.resource(Contract.STANDARD_PAYMENT),
                                '--payment-args', cli.payment_json)

    # First, deploy a contract that stores a function
    # and saves pointer to it under UREF "revert_test".
    # The stored function calls revert(2).
    test_contract = cli.resource("test_subcall_revert_define.wasm")

    first_deploy_hash = cli('deploy',
                            '--session', cli.resource(test_contract))
    block_hash = cli("propose")

    logging.info(f"""EXECUTING {' '.join(scala_cli.expand_args(["show-deploys", block_hash]))}""")
    deploys = scala_cli("show-deploys", block_hash)
    assert len(list(deploys)) == 1
    for deploy_info in deploys:
        assert deploy_info.deploy.deploy_hash == first_deploy_hash
        assert not deploy_info.is_error

    # Call by name
    deploy_hash = cli("deploy",
                      '--session-name', "revert_test")
    block_hash = cli("propose")

    deploys = scala_cli("show-deploys", block_hash)
    for deploy_info in deploys:
        assert deploy_info.deploy.deploy_hash == deploy_hash
        assert deploy_info.error_message == 'Exit code: 2'  # Expected: contract called revert(2)

    # Call by function address
    revert_test_addr = contract_address(first_deploy_hash, 0).hex()  # assume fn_store_id starts from 0
    deploy_hash = cli("deploy",
                      '--session-hash', revert_test_addr)
    block_hash = cli("propose")

    deploys = scala_cli("show-deploys", block_hash)
    for deploy_info in deploys:
        assert deploy_info.deploy.deploy_hash == deploy_hash
        assert deploy_info.error_message == 'Exit code: 2'
