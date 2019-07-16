import json
import time
import docker as docker_py
import logging

from contextlib import contextmanager


from test.cl_node.casperlabs_accounts import Account
from test.cl_node.wait import wait_for_blocks_count_at_least
from test.cl_node.casperlabs_network import OneNodeNetwork


def setup_account(num: int, node) -> Account:
    node.transfer_to_account(num, 1000000)
    return Account(num)


"""
[{'Pending_Deploys': 'casperlabs_casper_MultiParentCasper_pending_deploys 19.0',
  'Deploy Time': 2.2526628971099854,
  'Propose_Time': 31.691115379333496, 'Total_Time': 33.94377827644348, 'Block_Count': 22}]

249 - deploy, then 1 propose
[{'Deploy Time': 13.777348041534424, 
'Propose_Time': 0.23980474472045898, 
'Total_Time': 14.017152786254883, 'Block_Count': 252}]
17.8/sec

250 deploy/propose

[{'Deploy Time': 76.48123407363892, 
'Propose_Time': 2.384185791015625e-07, 
'Total_Time': 76.4812343120575, 'Block_Count': 502}]
3.3/sec
"""


@contextmanager
def docker_manager():
    docker_client = docker_py.from_env()
    try:
        yield docker_client
    finally:
        docker_client.volumes.prune()
        docker_client.networks.prune()


if __name__ == '__main__':

    ACCOUNT_COUNT = 250   # max

    logger = logging.getLogger()
    logger.setLevel(logging.DEBUG)
    with docker_manager() as docker_client:
        with OneNodeNetwork(docker_client) as onn:
            onn.create_cl_network()
            node = onn.docker_nodes[0]
            wait_for_blocks_count_at_least(node, 1, 1, node.timeout)
            node.use_python_client()

            genesis = Account('genesis')
            acct1 = Account(1)
            accts = [setup_account(i, node) for i in range(1, ACCOUNT_COUNT + 2)]

            transfer_args_json = json.dumps([{"account": acct1.public_key_hex},
                                         {"u32": 10}])
            transfer_args = node.client.abi.args_from_json(transfer_args_json)

            file_name = f'run_result_{time.time()}.txt'
            with open(file_name, 'w+') as f:
                f.write('Test Results:')

            loop_start = time.time()
            for acct in accts[1:]:
                print(f'Deploying from Acct: {acct.file_id}...')
                _, _ = node.client.deploy(from_address=acct.public_key_hex,
                                          session_contract='transfer_to_account.wasm',
                                          payment_contract='transfer_to_account.wasm',
                                          public_key=acct.public_key_binary_path,
                                          args=transfer_args)

            loop_end = time.time()

            # Must sleep to detect pending deploys
            # time.sleep(30)
            # metrics = node.get_metrics()
            print('Proposing...')
            response = node.client.propose()

            propose_finish = time.time()

            # lines = [l for l in metrics[1].splitlines() if 'casperlabs_casper_MultiParentCasper_pending_deploys' in l]

            result = {'Description': f'deploy from {ACCOUNT_COUNT} accounts to a single account and then propose once',
                      'Deploy Time': loop_end - loop_start,
                      'Propose_Time': propose_finish - loop_end,
                      'Total_Time': propose_finish - loop_start,
                      'Block_Count': node.client.get_blocks_count(10000)}
            with open(file_name, 'w+') as f:
                f.write(f'{result}')
