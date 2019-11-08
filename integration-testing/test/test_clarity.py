def test_clarity_running(one_node_network_with_clarity):
    """
    Feature file: clarity.feature
    Scenario: Clarity is running.
    """
    clarity_host = f"http://{one_node_network_with_clarity.clarity_node.name}:8080"
    driver = one_node_network_with_clarity.selenium_driver
    driver.get(clarity_host)
    assert driver.title == "CasperLabs Clarity"
