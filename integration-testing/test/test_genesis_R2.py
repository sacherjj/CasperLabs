from pytest import raises


def test(two_node_with_different_accounts_csv_network):
    """
    Check that two nodes started with different accounts.csv won't even create a network
    """
    with raises(Exception) as excinfo:
        two_node_with_different_accounts_csv_network.create_cl_network()
    assert "Failed to satisfy" in str(excinfo.value)
