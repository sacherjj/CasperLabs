from .cl_node.errors import NonZeroExitCodeError

import pytest

# Examples of query-state executed with the Scala client that result in errors:

# CasperLabs/docker $ ./client.sh node-0 propose
# Response: Success! Block 9d38836598... created and added.

# CasperLabs/docker $ ./client.sh node-0 query-state --block-hash '"9d"' --key '"a91208047c"' --path file.xxx --type hash
# NOT_FOUND: Cannot find block matching hash "9d"

# CasperLabs/docker$ ./client.sh node-0 query-state --block-hash 9d --key '"a91208047c"' --path file.xxx --type hash
# INVALID_ARGUMENT: Key of type hash must have exactly 32 bytes, 5 =/= 32 provided.

# CasperLabs/docker$ ./client.sh node-0 query-state --block-hash 9d --key 3030303030303030303030303030303030303030303030303030303030303030 --path file.xxx --type hash
# INVALID_ARGUMENT: Value not found: " Hash([48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48])"


# TODO: '30' * 32
KEY = '30' * 32
assert KEY == "3030303030303030303030303030303030303030303030303030303030303030"


@pytest.fixture(scope='module')
def node(one_node_network_module_scope):
    return one_node_network_module_scope.docker_nodes[0]

@pytest.fixture(scope='module')
def client(node):
    return node.d_client

@pytest.fixture(scope='module')
def block_hash(node):
    return node.deploy_and_propose()

block_hash_queries = [
    ({'block_hash': "9d", 'key': "a91208047c", 'path': "file.xxx", 'key_type': "hash"},
     "NOT_FOUND: Cannot find block matching"),

    ({                   'key': "a91208047c", 'path': "file.xxx", 'key_type': "hash"},
     "INVALID_ARGUMENT: Key of type hash must have exactly 32 bytes"),

    ({                   'key': KEY,          'path': "file.xxx", 'key_type': "hash"},
     "INVALID_ARGUMENT: Value not found"),
]

@pytest.mark.parametrize("query, expected", block_hash_queries)
def test_query_state_error(client, block_hash, query, expected):
    if not 'block_hash' in query:
        query['block_hash'] = block_hash

    with pytest.raises(NonZeroExitCodeError) as excinfo:
        response = client.query_state(**query)
    assert expected in excinfo.value.output

