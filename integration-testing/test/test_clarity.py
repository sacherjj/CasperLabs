import urllib.request


def test_clarity_running(one_node_network_with_clarity):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node twice to an existing network.
    """
    print("hh")
    f = urllib.request.urlopen("http://localhost:8080")
    content: str = f.read().decode("utf-8")
    print(content)
    assert content.find("<title>CasperLabs Clarity</title>") != -1
