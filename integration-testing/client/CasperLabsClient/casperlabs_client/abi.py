import json
import base64
from . import consensus_pb2 as consensus, state_pb2 as state


from google.protobuf import json_format

Arg = consensus.Deploy.Arg
Value = consensus.Deploy.Arg.Value


class ABI:
    """
    Serialize deploy arguments.
    """

    @staticmethod
    def optional_value(name, a):
        if a is None:
            return Arg(name=name, value=Value(optional_value=Value()))
        return Arg(name=name, value=Value(optional_value=a.value))

    @staticmethod
    def bytes_value(name, a: bytes):
        return Arg(name=name, value=Value(bytes_value=a))

    @staticmethod
    def account(name, a):
        if type(a) == bytes and len(a) == 32:
            return ABI.byte_array(name, a)
        if type(a) == str and len(a) == 64:
            return ABI.byte_array(name, bytes.fromhex(a))
        raise Exception("account must be 32 bytes or 64 characters long string")

    @staticmethod
    def int_value(name, i: int):
        return Arg(name=name, value=Value(int_value=i))

    @staticmethod
    def long_value(name, n: int):
        return Arg(name=name, value=Value(long_value=n))

    @staticmethod
    def big_int(name, n):
        return Arg(
            name=name, value=Value(big_int=state.BigInt(value=str(n), bit_width=512))
        )

    @staticmethod
    def string_value(name, s):
        return Arg(name=name, value=Value(string_value=s))

    @staticmethod
    def key_hash(name, h):
        return Arg(name=name, value=Value(key=state.Key(hash=state.Key.Hash(hash=h))))

    @staticmethod
    def key_address(name, a):
        return Arg(
            name=name, value=Value(key=state.Key(address=state.Key.Address(account=a)))
        )

    @staticmethod
    def key_uref(name, uref, access_rights):
        return Arg(
            name=name,
            value=Value(
                key=state.Key(
                    uref=state.Key.URef(uref=uref, access_rights=access_rights)
                )
            ),
        )

    @staticmethod
    def key_local(name, h):
        return Arg(name=name, value=Value(key=state.Key(local=state.Key.Local(hash=h))))

    @staticmethod
    def args(l: list):
        c = consensus.Deploy.Code(args=l)
        return c.args

    @staticmethod
    def args_from_json(s):
        parsed_json = ABI.hex2base64(json.loads(s))
        args = [json_format.ParseDict(d, consensus.Deploy.Arg()) for d in parsed_json]
        c = consensus.Deploy.Code(args=args)
        return c.args

    @staticmethod
    def args_to_json(args, **kwargs):
        lst = [
            json_format.MessageToDict(arg, preserving_proto_field_name=True)
            for arg in args
        ]
        return json.dumps(ABI.base64_2hex(lst), **kwargs)

    # Below methods for backwards compatibility

    @staticmethod
    def u32(name, n: int):
        return ABI.int_value(name, n)

    @staticmethod
    def u64(name, n: int):
        return ABI.long_value(name, n)

    @staticmethod
    def u512(name, n: int):
        return ABI.big_int(name, n)

    @staticmethod
    def byte_array(name, a):
        return Arg(name=name, value=Value(bytes_value=a))

    # These are kinds of Arg that are of type bytes.
    # We want to represent them in JSON in hex format, rather than base64.
    HEX_FIELDS = ("account", "bytes_value", "hash", "uref")

    # Standard protobuf JSON representation encodes fields of type bytes
    # in base64. We like base16 (hex) more. Below helper functions
    # help in converting output of protobuf JSON args serialization (base64 -> base16)
    # and in preparing user suplied JSON for parsing with protobuf (base16 -> base64).

    @staticmethod
    def h2b64(name, x):
        """
        Convert value of a field of a given name from hex to base64,
        if the field is known to be of the bytes type.
        """
        if type(x) == str and name in ABI.HEX_FIELDS:
            return base64.b64encode(bytes.fromhex(x)).decode("utf-8")
        return ABI.hex2base64(x)

    @staticmethod
    def hex2base64(d):
        """
        Convert decoded JSON list of args so fields of type bytes
        are in base64, in order to make it compatible with the format
        required by protobuf JSON parser.
        """
        if type(d) == list:
            return [ABI.hex2base64(x) for x in d]
        if type(d) == dict:
            return {k: ABI.h2b64(k, d[k]) for k in d}
        return d

    @staticmethod
    def b64_2h(name, x):
        """
        Helper function of base64_2hex.
        """
        if type(x) == str and name in ABI.HEX_FIELDS:
            return base64.b64decode(x).hex()
        return ABI.base64_2hex(x)

    @staticmethod
    def base64_2hex(d):
        """
        Convert JSON serialized args so fields of type bytes are shown in base16 (hex).
        """
        if type(d) == list:
            return [ABI.base64_2hex(x) for x in d]
        if type(d) == dict:
            return {k: ABI.b64_2h(k, d[k]) for k in d}
        return d
