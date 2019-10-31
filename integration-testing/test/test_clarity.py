import requests


def test_clarity_running(one_node_network_with_clarity):
    """
    Feature file: clarity.feature
    Scenario: Clarity is running.
    """
    f = requests.get("http://localhost:8080")
    assert f.text.find("<title>CasperLabs Clarity</title>") != -1
