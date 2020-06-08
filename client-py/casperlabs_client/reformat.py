import base64
import google.protobuf.json_format
import google.protobuf.text_format


def encode_base64(a: bytes):
    return str(base64.b64encode(a), "utf-8")


def hexify(o):
    """
    Convert protobuf message to text format with cryptographic keys and signatures in base 16.
    """
    return google.protobuf.text_format.MessageToString(o)


def jsonify(o):
    return google.protobuf.json_format.MessageToJson(o)
