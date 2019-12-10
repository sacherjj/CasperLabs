import logging
from casperlabs_local_net.cli import CLI
from casperlabs_local_net.common import Contract


def propose_and_get_cost(cli):
    block_hash = cli("propose")
    deployInfos = list(cli("show-deploys", block_hash))
    if len(deployInfos) != 1:
        raise Exception(f"Unexpected number of deploys: {len(deployInfos)}")
    deployInfo = deployInfos[0]
    if deployInfo.is_error:
        raise Exception(f"error_message: {deployInfo.error_message}")
    return deployInfo.cost, block_hash


# fmt: off


def test_upgrades_applied_major_versions(chainspec_upgrades_network_major):
    costs = check_upgrades_applied(chainspec_upgrades_network_major)
    assert len(costs) == 3, f"The number of distinct observable costs should equal 1 + count of upgraded cost tables, received {costs!r} instead"


def test_upgrades_applied_minor_versions(chainspec_upgrades_network_minor):
    costs = check_upgrades_applied(chainspec_upgrades_network_minor)
    assert len(costs) == 2, f"The number of distinct observable costs should equal 1 + count of upgraded cost tables, received {costs!r} instead"


def test_upgrades_applied_major_versions_etc(chainspec_upgrades_network_etc):
    costs = check_upgrades_applied(chainspec_upgrades_network_etc)
    assert len(costs) == 3, f"The number of distinct observable costs should equal 1 + count of upgraded cost tables, received {costs!r} instead"


def check_upgrades_applied(network):
    node = network.docker_nodes[0]

    cmd = "ls -la /etc/casperlabs /root/.casperlabs/chainspec /root/.casperlabs/chainspec/genesis"
    rc, output = node.exec_run(cmd)
    logging.info(f"============================ {cmd} => {rc}")
    logging.info(f"============================ [")
    logging.info(f"============================ {output}")
    logging.info(f"============================ ]")

    cli = CLI(network.docker_nodes[0], "casperlabs_client")
    account = cli.node.test_account

    cli.set_default_deploy_args(
        "--from", account.public_key_hex,
        "--private-key", cli.private_key_path(account),
        "--public-key", cli.public_key_path(account)
    )

    # First deploy
    cli("deploy", "--payment-amount", 10000000, "--session", cli.resource(Contract.COUNTER_DEFINE))
    propose_and_get_cost(cli)

    # When activation-point-rank of an upgrade is reached, and upgrade is executed,
    # the cost of execution should change.

    # We have spec of genesis, upgrade-1 and upgrade-2 in our custom chainspec
    # (in integration-testing/resources/test-chainspec)
    # Upgrades change cost of executing opcodes, so cost of execution of the same contract should change
    # after the upgrades are applied.
    costs = []
    versions = []

    # Currently test-chainspec activation points configured like below:

    # upgrade-1/manifest.toml:activation-point-rank = 20
    # upgrade-2/manifest.toml:activation-point-rank = 30

    # So, a number of deploys above 30 should be enough to activate both upgrades.
    offset = 2  # First deploy after genesis
    upgrade_1 = 20
    upgrade_2 = 30

    for i in range(1, 35):
        position = i + offset
        if position == upgrade_1 or position == upgrade_2:
            logging.info(f'Redeploying contract at position {position}')
            cli("deploy", "--payment-amount", 10000000, "--session", cli.resource(Contract.COUNTER_DEFINE))
            propose_and_get_cost(cli)
            # Add up, as another deploy shifts the block position
            offset += 1

        cli("deploy", "--payment-amount", 10000000, "--session", cli.resource(Contract.COUNTER_CALL))
        cost, block_hash = propose_and_get_cost(cli)
        if cost not in costs:
            logging.info(f"Execution cost at iteration {i}, is {cost}. ")
            costs.append(cost)
            version = cli("show-block", block_hash).summary.header.protocol_version
            versions.append(version)

    logging.info(f"Costs of execution: {' '.join(str(c) for c in costs)}")
    logging.info(f"Versions: {' '.join(str(v) for v in versions)}")
    return costs
