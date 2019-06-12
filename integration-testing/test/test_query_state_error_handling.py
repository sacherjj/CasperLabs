from .cl_node.wait import wait_for_blocks_count_at_least
from .cl_node.casperlabsnode import get_contract_state
from .cl_node.errors import NonZeroExitCodeError

import pytest
import casper_client
import logging

"""
aakoshh@af-dp:~/projects/CasperLabs/docker$ ./client.sh node-0 propose
Response: Success! Block 9d38836598... created and added.
aakoshh@af-dp:~/projects/CasperLabs/docker$ ./client.sh node-0 query-state --block-hash '"9d"' --key '"a91208047c"' --path file.xxx --type hash
NOT_FOUND: Cannot find block matching hash "9d"

aakoshh@af-dp:~/projects/CasperLabs/docker$ ./client.sh node-0 query-state --block-hash 9d --key '"a91208047c"' --path file.xxx --type hash
INVALID_ARGUMENT: Key of type hash must have exactly 32 bytes, 5 =/= 32 provided.

aakoshh@af-dp:~/projects/CasperLabs/docker$ ./client.sh node-0 query-state --block-hash 9d --key 3030303030303030303030303030303030303030303030303030303030303030 --path file.xxx --type hash
INVALID_ARGUMENT: Value not found: " Hash([48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48])"
aakoshh@af-dp:~/projects/CasperLabs/docker$
"""

def resource(file_name):
    return f'resources/{file_name}'


def test_query_state_error_handling(one_node_network):

    net = one_node_network
    node = net.docker_nodes[0]
    client = node.d_client

    with pytest.raises(NonZeroExitCodeError) as excinfo:
        response = client.queryState(blockHash = "9d", key = "a91208047c", path = "file.xxx", keyType = "hash")
    assert  "NOT_FOUND: Cannot find block matching" in excinfo.value.output


