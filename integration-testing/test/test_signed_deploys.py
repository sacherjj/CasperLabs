def test_deploy_with_valid_signature(one_node_signed_deploy_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with valid signature
    """
    node0 = one_node_signed_deploy_network.docker_nodes[0]
    node0.client.deploy(private_key="validator-0-private.pem",
                        public_key="validator-0-public.pem")


def test_deploy_with_invalid_signature(one_node_signed_deploy_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with invalid signature
    """

    node0 = one_node_signed_deploy_network.docker_nodes[0]
    try:
        node0.client.deploy(private_key="validator-0-private-invalid.pem",
                            public_key="validator-0-public-invalid.pem")
        assert False, "Deploy signed with invalid signatures has been passed"
    except Exception as ex:
        assert "Deploy with INVALID_ARGUMENT: Invalid deploy signature." in node0.logs()
