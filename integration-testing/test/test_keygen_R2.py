def disable_test_scala_keygen(two_node_network_with_scala_generated_keys):
    # Once the fixture has been successfully created we know that the keys are fine:
    # the nodes started, found their peer and a show-blocks was successfull
    # in retrieving a genesis block.
    #
    # However, the node cannot propose because it has no stake:
    #
    # (Validation.scala:93)  …alidation.Validation.ignore [52:ingress-io-52 ] Ignoring block=ac6c508bd3... because block creator 6629f1ffef... has 0 weight.
    # Closing gRPC call from source=/172.18.0.1:47762 to method=io.casperlabs.node.api.control.ControlService/Propose with get_code=INVALID_ARGUMENT: desc=Invalid block: InvalidUnslashableBlock
    pass


def disable_test_python_keygen(two_node_network_with_python_generated_keys):
    pass
