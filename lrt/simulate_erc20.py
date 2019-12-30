#!/usr/bin/env python3

import random
import threading
from erc20 import ERC20, Node, Agent, DeployedERC20, transfer_clx


def check_total_token_amount(node, abc, deployer, agents, amount):
    n = sum(
        deployer.on(node).call_contract(abc.balance(agent.public_key_hex))
        for agent in agents + [deployer]
    )
    assert n == amount


def random_node(nodes):
    return random.sample(nodes, 1)[0]


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
    boss = faucet.on(node)
    abc = boss.call_contract(
        ERC20(token_name).deploy(initial_balance=total_token_supply)
    )

    balance = boss.call_contract(abc.balance(faucet.public_key_hex))

    # Initially deployer's balance should be equal the total token supply
    assert balance == total_token_supply

    for agent in agents:
        transfer_clx(faucet.on(node), agent.public_key_hex, initial_agent_clx_funds)

    # Transfer tokens from deployer to agents
    for agent in agents:
        boss.call_contract(
            abc.transfer(
                sender_private_key=faucet.private_key,
                recipient_public_key_hex=agent.public_key_hex,
                amount=tokens_per_agent,
            )
        )
        balance = boss.call_contract(abc.balance(agent.public_key_hex))
        assert balance == tokens_per_agent

    check_total_token_amount(node, abc, faucet, agents, total_token_supply)


def transfer_tokens_between_agents(
    nodes,
    faucet,
    agents,
    token_name,
    total_token_supply,
    number_of_iterations,
    max_transfer,
):
    """
    Execute transfers between random agents, check total tokens in the system stays the same.
    """
    abc = DeployedERC20.create(faucet.on(nodes[0]), token_name, True)
    for i in range(number_of_iterations):
        node = random_node(nodes)
        sender, recipient = random.sample(agents, 2)
        sender.on(node).call_contract(
            abc.transfer(
                sender_private_key=sender.private_key,
                recipient_public_key_hex=recipient.public_key_hex,
                amount=random.randint(1, max_transfer),
            )
        )
        check_total_token_amount(node, abc, faucet, agents, total_token_supply)


def run_agent(
    agent,
    nodes,
    faucet,
    agents,
    token_name,
    total_token_supply,
    number_of_iterations,
    max_transfer,
):
    """
    Transfer random amount of tokens to a random agent, repeat number_of_iterations times.
    """
    abc = DeployedERC20.create(faucet.on(nodes[0]), token_name, False)
    for i in range(number_of_iterations):
        recipient = random.sample(agents, 1)[0]
        node = random_node(nodes)
        agent.on(node).call_contract(
            abc.transfer(
                sender_private_key=agent.private_key,
                recipient_public_key_hex=recipient.public_key_hex,
                amount=random.randint(1, max_transfer),
            )
        )
        check_total_token_amount(
            node, abc, faucet, [agent] + agents, total_token_supply
        )


def run_erc20_simulation(
    nodes,
    faucet,
    agents,
    token_name,
    total_token_supply,
    initial_agent_clx_funds,
    tokens_per_agent,
    number_of_iterations,
    max_transfer,
):
    print("Deploy ERC20 and initialize simulation")
    initialize_erc20_simulation(
        nodes[0],
        faucet,
        agents,
        token_name,
        total_token_supply,
        initial_agent_clx_funds,
        tokens_per_agent,
    )

    threads = [
        threading.Thread(
            target=run_agent,
            args=(
                agent,
                nodes,
                faucet,
                [a for a in agents if a != agent],
                token_name,
                total_token_supply,
                number_of_iterations,
                max_transfer,
            ),
        )
        for agent in agents
    ]
    print(f"Created {len(threads)} threads")

    for t in threads:
        t.start()

    print("Started all threads.")

    for t in threads:
        t.join()

    print("End of simulation.")


if __name__ == "__main__":
    node = Node("localhost")
    faucet = Agent("faucet-account")
    run_erc20_simulation(
        [node],
        faucet,
        [Agent("account-0"), Agent("account-1"), Agent("account-2")],
        "ABC",
        5000,
        10 ** 8,
        500,
        50,
        3,
    )
    faucet.on(node).node.client.propose()
