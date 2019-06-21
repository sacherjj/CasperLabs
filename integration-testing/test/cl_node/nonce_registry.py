from collections import defaultdict
import threading

class NonceRegistry:
    _registry = None
    _lock = threading.Lock()

    @staticmethod
    def reset():
        NonceRegistry._registry = defaultdict(lambda: 1)

    @staticmethod
    def next(address):
        if NonceRegistry._registry is None:
            raise Exception("Registry is empty! Did you forget to call `reset()`?")
        with NonceRegistry._lock:
            nonce = NonceRegistry._registry[address]
            # Alternatively there could be a `with_nonce` method that that accepts a
            # lambda, passes the nonce to it, and only increases if it succeeds.
            NonceRegistry._registry[address] = nonce + 1
            return nonce




