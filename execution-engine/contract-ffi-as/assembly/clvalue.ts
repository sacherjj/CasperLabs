import {toBytesArrayU8, toBytesString, toBytesI32} from "./bytesrepr";
import {U512} from "./bignum";
import {URef} from "./uref";
import {Key} from "./key";

export enum CLTypeTag {
    Bool = 0,
    I32 = 1,
    I64 = 2,
    U8 = 3,
    U32 = 4,
    U64 = 5,
    U128 = 6,
    U256 = 7,
    U512 = 8,
    Unit = 9,
    String = 10,
    Key = 11,
    Uref = 12,
    Option = 13,
    List = 14,
    Fixed_list = 15,
    Result = 16,
    Map = 17,
    Tuple1 = 18,
    Tuple2 = 19,
    Tuple3 = 20,
    Any = 21,
}

export class CLValue {
    bytes: u8[];
    tag: u8;

    constructor(bytes: u8[], tag: u8) {
        this.bytes = bytes;
        this.tag = tag;
    }

    static fromString(s: String): CLValue {
        return new CLValue(toBytesString(s), <u8>CLTypeTag.String);
    }

    static fromU512(value: U512): CLValue {
        return new CLValue(value.toBytes(), <u8>CLTypeTag.U512);
    }

    static fromI32(value: i32): CLValue {
        return new CLValue(toBytesI32(value), <u8>CLTypeTag.I32);
    }

    static fromKey(key: Key): CLValue{
        return new CLValue(key.toBytes(), <u8>CLTypeTag.Key);
    }

    static fromURef(uref: URef): CLValue {
        return new CLValue(uref.toBytes(), <u8>CLTypeTag.Uref);
    }

    toBytes(): u8[] {
        let data = toBytesArrayU8(this.bytes);
        data.push(<u8>this.tag);
        return data;
    }

    // new_turef equivalent
    // write(): URef{
    //   // make call to write passing this CLValue and the provided URef
    // }
}
