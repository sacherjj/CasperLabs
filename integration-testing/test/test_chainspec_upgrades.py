import pytest
from casperlabs_local_net.cli import CLI
from casperlabs_local_net.common import Contract


@pytest.fixture()
def cli(chainspec_upgrades_network):
    return CLI(chainspec_upgrades_network.docker_nodes[0], "casperlabs_client")


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

def test_upgrades_applied(cli):
    account = cli.node.test_account

    cli.set_default_deploy_args(
        "--from", account.public_key_hex,
        "--private-key", cli.private_key_path(account),
        "--public-key", cli.public_key_path(account),
    )

    cli("deploy", "--payment-amount", 10000000, "--session", cli.resource(Contract.COUNTER_DEFINE))
    propose_and_get_cost(cli)

    cli("deploy", "--payment-amount", 10000000, "--session", cli.resource(Contract.COUNTER_CALL))
    cost = propose_and_get_cost(cli)

    for i in range(1, 35):
        cli("deploy", "--payment-amount", 10000000, "--session", cli.resource(Contract.COUNTER_CALL))
        c = propose_and_get_cost(cli)
        if c != cost:
            # TODO:
            # When activation-point-rank of an upgrade is reached,
            # and upgrade is executed,
            # cost of execution should change
            raise Exception(f"Cost different at iteration {i}, was {cost}, now {c}. ")
