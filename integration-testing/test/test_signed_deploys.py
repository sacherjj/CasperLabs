

def test_deploy_with_valid_signature(one_node_signed_deploy_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with valid signature
    """
    node0 = one_node_signed_deploy_network.docker_nodes[0]
    node0.client.deploy()


def test_deploy_with_invalid_signature(one_node_signed_deploy_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with invalid signature
    """
    node0 = one_node_signed_deploy_network.docker_nodes[0]
    try:
        node0.client.deploy(private_key="validator-0-private-invalid.pem",
                            public_key="validator-0-public-invalid.pem")
        assert False , "Deploy signed with invalid signatures has been passed"
    except:
        assert True
