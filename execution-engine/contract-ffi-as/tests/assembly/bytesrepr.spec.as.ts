import { fromBytesU64, toBytesU64,
         fromBytesStringList, toBytesStringList,
         fromBytesU32, toBytesU32,
         fromBytesU8, toBytesU8,
         toBytesMap, fromBytesMap,
         toBytesPair,
         toBytesString, fromBytesString,
         toBytesVecT } from "../../assembly/bytesrepr";
import { CLValue } from "../../assembly/clvalue";
import { Key, KeyVariant } from "../../assembly/key";
import { URef, AccessRights } from "../../assembly/uref";
import { Option } from "../../assembly/option";
import { hex2bin, isArraysEqual } from "../utils/helpers";
import { typedToArray, arrayToTyped } from "../../assembly/utils";
import { Pair } from "../../assembly/pair";

// ERROR: TypeError: Cannot create property 'shouldSerializeU64' on number '3' in @assemblyscript/loader
// export class BytesReprTest {

export function testDeSerU8(): bool {
    const truth: u8[] = [222];
    let ser = toBytesU8(222);
    assert(isArraysEqual(ser, truth));
    let deser = fromBytesU8(arrayToTyped(ser));
    assert(ser !== null);
    return <u8>deser == <u8>222;
}

export function xtestDeSerU8_Zero(): bool {
    // Used for deserializng Weight (for example)
    // NOTE: Currently probably unable to check if `foo(): U8 | null` result is null
    const truth: u8[] = [0];
    let ser = toBytesU8(0);
    assert(isArraysEqual(ser, truth));
    let deser = fromBytesU8(arrayToTyped(ser));
    return deser == <U8>0;
}

export function testDeSerU32(): bool {
    const truth: u8[] = [239, 190, 173, 222];
    let deser = toBytesU32(3735928559);
    assert(isArraysEqual(deser, truth));
    let ser = fromBytesU32(arrayToTyped(deser));
    assert(ser !== null);
    return <u32>ser == <u32>0xdeadbeef;
}
  
export function testDeSerZeroU32(): bool {
    const truth: u8[] = [0, 0, 0, 0];
    let ser = toBytesU32(0);
    assert(isArraysEqual(ser, truth));
    let deser = fromBytesU32(arrayToTyped(ser));
    // WTF: `ser !== null` is false when ser === <U32>0
    assert(ser != null);
    return deser === <U32>0;
}

export function testDeserializeU64_1024(): bool {
    const truth = hex2bin("0004000000000000");
    var value = fromBytesU64(truth);
    return value == <U64>1024;
}

export function testDeserializeU64_zero(): bool {
    const truth = hex2bin("0000000000000000");
    var value = fromBytesU64(truth);
    return value == <U64>0;
}

export function testDeserializeU64_u32max(): bool {
    const truth = hex2bin("ffffffff00000000");
    const value = fromBytesU64(truth);
    return value == <U64>0xffffffff;
}

export function testDeserializeU64_u32max_plus1(): bool {
    
    const truth = hex2bin("0000000001000000");
    const value = fromBytesU64(truth);
    return value == <U64>4294967296;
}

// xtest marks this test as skipped
export function xtestDeserializeU64_u64max(): bool {
    const truth = hex2bin("fffffffffffffffe");
    const value = fromBytesU64(truth);
    assert(value !== null);
    // NOTE: It seems like U64/u64 is not represented as a real u64 value,
    // so I suspect this actually overflows and compares X == u32max.
    let u64_max = <u64>18446744073709551614;
    return value == <U64>u64_max;
}

export function testDeSerListOfStrings(): bool {
    const truth = hex2bin("03000000030000006162630a0000003132333435363738393006000000717765727479");
    const maybeResult = fromBytesStringList(truth);
    assert(maybeResult != null);
    const result = <String[]>maybeResult;

    assert(isArraysEqual(result, <String[]>[
        "abc",
        "1234567890",
        "qwerty",
    ]));
    
    let lhs = toBytesStringList(result);
    let rhs = typedToArray(truth);
    return isArraysEqual(lhs, rhs);
};

export function testDeSerEmptyListOfStrings(): bool {
    const truth = hex2bin("00000000");
    const maybeResult = fromBytesStringList(truth);
    return isArraysEqual(<String[]>maybeResult, <String[]>[]);
};

export function testSerializeMap(): bool {
    // let mut m = BTreeMap::new();
    // m.insert("Key1".to_string(), "Value1".to_string());
    // m.insert("Key2".to_string(), "Value2".to_string());
    // let truth = m.to_bytes().unwrap();
    const truth = hex2bin(
        "02000000040000004b6579310600000056616c756531040000004b6579320600000056616c756532"
    );
    const map: u8[][] = [
        toBytesPair(toBytesString("Key1"), toBytesString("Value1")),
        toBytesPair(toBytesString("Key2"), toBytesString("Value2")),
    ];

    const serialized = toBytesMap(map);
    assert(isArraysEqual(serialized, typedToArray(truth)));

    const maybeDeser = fromBytesMap<String, String>(
        arrayToTyped(serialized),
        fromBytesString,
        toBytesString,
        fromBytesString,
        toBytesString);
    
    assert(maybeDeser !== null);
    let deser = <Array<Pair<String, String>>>maybeDeser;
    

    let res1 = false;
    let res2 = false;
    for (let i = 0; i < deser.length; i++) {
        if (deser[i].first == "Key1" && deser[i].second == "Value1") {
            res1 = true;
        }
        if (deser[i].first == "Key2" && deser[i].second == "Value2") {
            res2 = true;
        }
    }
    assert(res1);
    assert(res2);
    return deser.length == 2;
}

export function testToBytesVecT(): bool {
    // let args = ("get_payment_purse",).parse().unwrap().to_bytes().unwrap();
    const truth = hex2bin("0100000015000000110000006765745f7061796d656e745f70757273650a");
    let serialized = toBytesVecT<CLValue>([
        CLValue.fromString("get_payment_purse"),
    ]);
    return isArraysEqual(serialized, typedToArray(truth));
}

export function testKeyOfURefVariantSerializes(): bool {
    // URef with access rights
    const truth = hex2bin("022a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a0107");
    const urefBytes = hex2bin("2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a");
    let uref = new URef(urefBytes, AccessRights.READ_ADD_WRITE);
    let key = Key.fromURef(uref);
    let serialized = key.toBytes();

    return isArraysEqual(serialized, typedToArray(truth));
};

export function testDeSerString(): bool {
    // Rust: let bytes = "hello_world".to_bytes().unwrap();
    const truth = hex2bin("0b00000068656c6c6f5f776f726c64");

    const ser = toBytesString("hello_world");
    assert(isArraysEqual(ser, typedToArray(truth)));

    const deser = fromBytesString(arrayToTyped(ser));
    assert(deser !== null);
    return deser == "hello_world";
}

export function testDecodeURefFromBytesWithoutAccessRights(): bool {
    const truth = hex2bin("2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a00");
    let uref = URef.fromBytes(truth);
    assert(uref !== null);
    assert(isArraysEqual(typedToArray(uref.getBytes()), <u8[]>[
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42,
    ]));
    assert(uref.getAccessRights() === AccessRights.NONE);
    let serialized = uref.toBytes();
    return isArraysEqual(serialized, typedToArray(truth));
}

export function testDecodeURefFromBytesWithAccessRights(): bool {
    const truth = hex2bin("2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a0107");
    const maybeURef = URef.fromBytes(truth);
    assert(maybeURef !== null);
    const uref = <URef>maybeURef;
    assert(isArraysEqual(typedToArray(uref.getBytes()), <u8[]>[
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42,
    ]));
    return uref.getAccessRights() == 0x07; // NOTE: 0x07 is READ_ADD_WRITE
}

export function testDecodedOptionalIsNone(): bool {
    let optionalSome = new Uint8Array(10);
    optionalSome[0] = 0;
    let res = Option.fromBytes(optionalSome);
    assert(res.isNone(), "option should be NONE");
    return !res.isSome();
};

export function testDecodedOptionalIsSome(): bool {
    let optionalSome = new Uint8Array(10);
    for (let i = 0; i < 10; i++) {
        optionalSome[i] = i + 1;
    }
    let res = Option.fromBytes(optionalSome);
    assert(res !== null);
    let unwrapped = res.unwrap();
    assert(unwrapped !== null, "unwrapped should not be null");
    let values = <Uint8Array>unwrapped;
    return isArraysEqual(typedToArray(values), [2, 3, 4, 5, 6, 7, 8, 9, 10]);
};
