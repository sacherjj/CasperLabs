import requests
import os


def test_clarity_running(one_node_network_with_clarity):
    """
    Feature file: clarity.feature
    Scenario: Clarity is running.
    """
    in_docker = os.getenv("IN_DOCKER") == "true"
    if in_docker:
        # If these integration tests are running in a docker container, then we need connect the docker container
        # to the network of clarity
        clarity_node = one_node_network_with_clarity.clarity_node
        network_name = clarity_node.config.network
        network = clarity_node.network_from_name(network_name)
        # Gets name of container name of integration_testing
        integration_test_node = os.getenv("HOSTNAME")
        network.connect(integration_test_node)

        host = f"http://{one_node_network_with_clarity.clarity_node.name}:8080"
        content = requests.get(host).text
        # Disconnect container from the network, so that we can prune the network
        network.disconnect(integration_test_node)
    else:
        host = "http://127.0.0.1:8080"
        content = requests.get(host).text
    assert content.find("<title>CasperLabs Clarity</title>") != -1
