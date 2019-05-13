import grpc

class ProxyServicer:

    def __init__(self, node_host: str,
                       node_port: int,
                       service_stub = None,
                       unary_stream_methods = [],
                       pre_callback = lambda name, request: request,
                       post_callback = lambda name, request, response: None):

        self.node_host = node_host
        self.node_port = node_port
        self.service_stub = service_stub
        self.unary_stream_methods = unary_stream_methods
        self.pre_callback = pre_callback
        self.post_callback = post_callback


    def __getattr__(self, name):
        node_address = f"{self.node_host}:{self.node_port}"

        def f(request, context):
            with grpc.insecure_channel(node_address) as channel:
                r = self.pre_callback(name, request)
                v = getattr(self.service_stub(channel), name)(r)
                self.post_callback(name, request, v)
                return v

        def g(request, context):
            with grpc.insecure_channel(node_address) as channel:
                r = self.pre_callback(name, request)
                vs = [x for x in getattr(self.service_stub(channel), name)(r)]
                self.post_callback(name, request, vs)
                yield from vs

        return g if name in self.unary_stream_methods else f
        

