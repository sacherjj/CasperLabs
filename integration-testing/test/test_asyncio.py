import pytest
from casperlabs_client.casperlabs_client_aio import CasperLabsClientAIO


@pytest.mark.asyncio
async def test_query_state(node):
    client = CasperLabsClientAIO(node.node_host, node.grpc_external_docker_port)
    result = await client.show_blocks()
    assert len(list(result))
