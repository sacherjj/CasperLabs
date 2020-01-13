import argparse
import logging
import random
from dramatiq import actor
from erc20 import ERC20, Node, Agent, DeployedERC20

AGENTS = [Agent("account-0"), Agent("account-1"), Agent("account-2")]
NODES = [Node("localhost")]
FAUCET = Agent("faucet-account")

TOKEN_NAME = "ABC"
TOTAL_TOKEN_SUPPLY = 200000
TOKENS_PER_AGENT = 10000
MAX_TRANSFER = 100

INITIAL_AGENT_CLX_FUNDS = 10 ** 7


@actor
def transfer_tokens(
    sender_name, recipient_name, amount, token_name=TOKEN_NAME, wait_for_processed=False
):
    try:
        sender = Agent(sender_name)
        recipient = Agent(recipient_name)

        deployer = FAUCET.on(NODES[0])
        abc = DeployedERC20.create(deployer, token_name)

        sender.on(random.sample(NODES, 1)[0]).call_contract(
            abc.transfer(
                sender_private_key=sender.private_key,
                recipient_public_key_hex=recipient.public_key_hex,
                amount=amount,
            ),
            wait_for_processed=wait_for_processed,
        )
    except Exception as e:
        logging.error(
            f"transfer_tokens({sender_name}, {recipient_name}, {amount}) => {str(e)}"
        )


def initialize_erc20_simulation(
    node,
    faucet,
    agents,
    token_name,
    total_token_supply,
    initial_agent_clx_funds,
    tokens_per_agent,
):
    """
    Deploy ERC20 smart contract from the faucet account.
    Transfer initial_agent_clx_funds CLX to each agent.
    Transfer tokens_per_agent ERC20 tokens to each agent.
    """
    deployer = faucet.on(node)
    deployer.call_contract(ERC20(token_name).deploy(initial_balance=total_token_supply))
    abc = DeployedERC20.create(deployer, token_name)

    balance = deployer.query(abc.balance(faucet.public_key_hex))

    # Initially deployer's balance should be equal the total token supply
    assert balance == total_token_supply

    for agent in agents:
        faucet.on(node).transfer_clx(agent.public_key_hex, initial_agent_clx_funds)

    # Transfer tokens from deployer to agents
    for agent in agents:
        deployer.call_contract(
            abc.transfer(
                sender_private_key=faucet.private_key,
                recipient_public_key_hex=agent.public_key_hex,
                amount=tokens_per_agent,
            )
        )
        balance = deployer.query(abc.balance(agent.public_key_hex))
        assert balance == tokens_per_agent


if __name__ == "__main__":
    parser = argparse.ArgumentParser("Simulate ERC20 token sale")
    parser.add_argument("command", choices=("deploy", "run"))
    args = parser.parse_args()

    if args.command == "deploy":
        initialize_erc20_simulation(
            NODES[0],
            FAUCET,
            AGENTS,
            TOKEN_NAME,
            TOTAL_TOKEN_SUPPLY,
            INITIAL_AGENT_CLX_FUNDS,
            TOKENS_PER_AGENT,
        )

    if args.command == "run":
        for _ in range(10):
            sender, recipient = random.sample(AGENTS, 2)
            amount = random.randint(1, MAX_TRANSFER)
            # transfer_tokens.send(sender.name, recipient.name, amount)
            transfer_tokens(sender.name, recipient.name, amount)
