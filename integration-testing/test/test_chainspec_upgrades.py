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
    return deployInfo.cost


# fmt: off


def test_upgrades_applied_major_versions(chainspec_upgrades_network_major):
    check_upgrades_applied(chainspec_upgrades_network_major)


def disabled_test_upgrades_applied_minor_versions(chainspec_upgrades_network_minor):
    check_upgrades_applied(chainspec_upgrades_network_minor)


def check_upgrades_applied(network):
    cli = CLI(network.docker_nodes[0], "casperlabs_client")
    account = cli.node.test_account

    cli.set_default_deploy_args(
        "--from", account.public_key_hex,
        "--private-key", cli.private_key_path(account),
        "--public-key", cli.public_key_path(account),
    )

    # When activation-point-rank of an upgrade is reached, and upgrade is executed,
    # the cost of execution should change.

    cli("deploy", "--payment-amount", 10000000, "--session", cli.resource(Contract.COUNTER_DEFINE))
    propose_and_get_cost(cli)

    # We have spec of genesis, upgrade-1 and upgrade-2 in our custom chainspec
    # (in integration-testing/resources/test-chainspec)
    # Upgrades change cost of excuting opcodes, so cost of execution of the same contract should change
    # after the upgrades are applied.
    costs = []

    # Currently test-chainspec activation points configured like below:

    # upgrade-1/manifest.toml:activation-point-rank = 20
    # upgrade-2/manifest.toml:activation-point-rank = 30

    # So, a number of deploys above 30 should be enough to activate both upgrades.
    for i in range(1, 35):
        cli("deploy", "--payment-amount", 10000000, "--session", cli.resource(Contract.COUNTER_CALL))
        cost = propose_and_get_cost(cli)
        if cost not in costs:
            logging.info(f"Execution cost at iteration {i}, is {cost}. ")
            costs.append(cost)

    logging.info(f"Costs of execution: {' '.join(str(c) for c in costs)}")

    assert len(costs) == 3, "For 2 upgrades after genesis that change opcodes' cost we should see 3 different execution costs"
