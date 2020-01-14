import argparse
import logging
import random
import jsonpickle
from dramatiq import actor
from erc20 import ERC20, Node, Agent, DeployedERC20

AGENTS = [Agent("account-0"), Agent("account-1"), Agent("account-2")]
NODES = [Node("localhost")]
ERC20_DEPLOYER = Agent("faucet-account")

TOKEN_NAME = "ABC"
TOTAL_TOKEN_SUPPLY = 200000
TOKENS_PER_AGENT = 10000
MAX_TRANSFER = 100

INITIAL_AGENT_CLX_FUNDS = 10 ** 8


@actor
def transfer_tokens(
    pickled_erc20_deployer,
    token_name,
    node_host,
    node_port,
    pickled_sender,
    pickled_recipient,
    amount,
    wait_for_processed=False,
):
    try:
        erc20_deployer = jsonpickle.decode(pickled_erc20_deployer)
        node = Node(node_host, node_port)
        sender = jsonpickle.decode(pickled_sender)
        recipient = jsonpickle.decode(pickled_recipient)

        deployed_erc20 = DeployedERC20.create(erc20_deployer.on(node), token_name)

        sender.on(node).call_contract(
            deployed_erc20.transfer(
                sender_private_key=sender.private_key,
                recipient_public_key_hex=recipient.public_key_hex,
                amount=amount,
            ),
            wait_for_processed=wait_for_processed,
        )
    except Exception as e:
        logging.error(
            f"transfer_tokens({sender.public_key_hex}, {recipient.public_key_hex}, {amount}) => {str(e)}"
        )


def initialize_erc20_simulation(
    node,
    deployer,
    agents,
    token_name,
    total_token_supply,
    initial_agent_clx_funds,
    tokens_per_agent,
):
    """
    Deploy ERC20 smart contract from the deployer account.
    Transfer initial_agent_clx_funds CLX to each agent.
    Transfer tokens_per_agent ERC20 tokens to each agent.
    """
    bound_deployer = deployer.on(node)
    bound_deployer.call_contract(
        ERC20(token_name).deploy(initial_balance=total_token_supply)
    )
    abc = DeployedERC20.create(bound_deployer, token_name)

    balance = bound_deployer.query(abc.balance(deployer.public_key_hex))

    # Initially deployer's balance should be equal the total token supply
    assert balance == total_token_supply

    for agent in agents:
        bound_deployer.transfer_clx(agent.public_key_hex, initial_agent_clx_funds)

    # Transfer tokens from deployer to agents
    for agent in agents:
        bound_deployer.call_contract(
            abc.transfer(
                sender_private_key=deployer.private_key,
                recipient_public_key_hex=agent.public_key_hex,
                amount=tokens_per_agent,
            )
        )
        balance = bound_deployer.query(abc.balance(agent.public_key_hex))
        assert balance == tokens_per_agent


if __name__ == "__main__":
    parser = argparse.ArgumentParser("Simulate ERC20 token sale")
    parser.add_argument("command", choices=("deploy", "run"))
    args = parser.parse_args()

    if args.command == "deploy":
        initialize_erc20_simulation(
            NODES[0],
            ERC20_DEPLOYER,
            AGENTS,
            TOKEN_NAME,
            TOTAL_TOKEN_SUPPLY,
            INITIAL_AGENT_CLX_FUNDS,
            TOKENS_PER_AGENT,
        )

    if args.command == "run":
        for _ in range(10):
            sender, recipient = random.sample(AGENTS, 2)
            node = random.sample(NODES, 1)[0]
            amount = random.randint(1, MAX_TRANSFER)

            transfer_tokens(
                jsonpickle.encode(ERC20_DEPLOYER),
                TOKEN_NAME,
                node.host,
                node.port,
                jsonpickle.encode(sender),
                jsonpickle.encode(recipient),
                amount,
            )
