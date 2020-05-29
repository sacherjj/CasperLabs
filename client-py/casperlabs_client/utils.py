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


def print_blocks(response, element_name="block"):
    count = 0
    for block in response:
        print(f"------------- {element_name} {count} ---------------")
        print_block(block)
        print("-----------------------------------------------------\n")
        count += 1
    print("count:", count)


def print_block(block):
    print(hexify(block))
