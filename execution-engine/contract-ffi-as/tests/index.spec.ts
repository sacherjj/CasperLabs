import "assemblyscript/std/portable";
import {toBytesU32, fromBytesU32, toBytesMap, serializeArguments, toBytesString, fromBytesString, toBytesPair } from "../assembly/bytesrepr";
import {AccessRights, URef} from "../assembly/uref";
import {Option} from "../assembly/option";
import {Key} from "../assembly/key";
import {CLValue} from "../assembly/clvalue";

import test from "ava";
import {hex2bin} from "./utils";

test('decoded optional is some', t => {
    let optionalSome = new Uint8Array(10);
    for (let i = 0; i < 10; i++) {
        optionalSome[i] = i + 1;
    }
    let res = Option.fromBytes(optionalSome);
    t.not(res, null);
    let unwrapped = res.unwrap();
    t.not(unwrapped, null, "unwrapped should not be null");
    t.deepEqual(Array.from(unwrapped.values()), [2, 3, 4, 5, 6, 7, 8, 9, 10]);
});

test('decoded optional is none', t => {
    let optionalSome = new Uint8Array(10);
    optionalSome[0] = 0;
    let res = Option.fromBytes(optionalSome);
    t.assert(res.isNone(), "option should be NONE");
});

test("decode uref from bytes with access rights", t => {
    const truth = hex2bin("2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a0107");
    let uref = URef.fromBytes(truth);
    t.not(uref, null);
    t.deepEqual(Array.from(uref.getBytes()), [
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42,
    ]);
    t.is(uref.getAccessRights(), 0x07); // NOTE: 0x07 is READ_ADD_WRITE
})

test("decode uref from bytes without access rights", t => {
    const truth = hex2bin("2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a00");
    let uref = URef.fromBytes(truth);
    t.not(uref, null);
    t.deepEqual(Array.from(uref.getBytes()), [
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42,
    ]);
    t.is(uref.getAccessRights(), AccessRights.NONE);
    let serialized = uref.toBytes();
    t.deepEqual(Array.from(serialized), Array.from(truth));
})

test("serialize u32", t => {
    const truth = [239, 190, 173, 222];
    let res = toBytesU32(3735928559);
    t.deepEqual(Array.from(res), truth);

    t.deepEqual(fromBytesU32(res), 0xdeadbeef);
})

test("serialize string", t => {
    // Rust: let bytes = "hello_world".to_bytes().unwrap();
    const truth = hex2bin("0b00000068656c6c6f5f776f726c64");

    var res = toBytesString("hello_world");
    t.deepEqual(res, Array.from(truth));

    var res = fromBytesString(res);
    t.deepEqual(res, "hello_world");
})

test("key of uref variant serializes", t => {
    // URef with access rights
    const truth = hex2bin("022a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a0107");
    const urefBytes = hex2bin("2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a");
    let uref = new URef(urefBytes, AccessRights.READ_ADD_WRITE);


    let key = Key.fromURef(uref);
    let serialized = key.toBytes();
    t.deepEqual(Array.from(serialized), Array.from(truth));
});

test("serialize args", t => {
    // let args = ("get_payment_purse",).parse().unwrap().to_bytes().unwrap();
    const truth = hex2bin("0100000015000000110000006765745f7061796d656e745f70757273650a");
    let serialized = serializeArguments([
        CLValue.fromString("get_payment_purse"),
    ]);
    t.deepEqual(Array.from(serialized), Array.from(truth));
})

test("serialize map", t => {
    // let mut m = BTreeMap::new();
    // m.insert("Key1".to_string(), "Value1".to_string());
    // m.insert("Key2".to_string(), "Value2".to_string());
    // let truth = m.to_bytes().unwrap();
    const truth = hex2bin(
        "02000000040000004b6579310600000056616c756531040000004b6579320600000056616c756532"
    );
    const map = [
        toBytesPair(toBytesString("Key1"), toBytesString("Value1")),
        toBytesPair(toBytesString("Key2"), toBytesString("Value2")),
    ];

    const serialized = toBytesMap(map);
    t.deepEqual(Array.from(serialized), Array.from(truth));
})
