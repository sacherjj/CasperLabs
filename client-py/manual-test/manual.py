import pytest

from casperlabs_client import CasperLabsClient
from casperlabs_client.commands import show_peers_cmd


@pytest.fixture()
def casperlabs_client():
    return CasperLabsClient()


def test_show_peers(casperlabs_client):
    response = casperlabs_client.show_peers()
    print(response)


def test_show_peers_cli(casperlabs_client):
    show_peers_cmd.method(casperlabs_client, dict())
