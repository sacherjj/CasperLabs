import { hex2bin } from "../utils/helpers";
import { U512, BigNum } from "../../assembly/bignum";
import { checkArraysEqual } from "../../assembly/utils";
import { fromBytesU64 } from "../../assembly/bytesrepr";
import { typedToArray } from "../../assembly/utils";

export function testSerializeU512_3BytesWide(): bool {
    let truth = hex2bin("03807801");
    let num = U512.fromBytes(truth);
    assert(num.getValue() === <U64>96384);
    const bytes = num.toBytes();
    return checkArraysEqual(bytes, typedToArray(truth));
};

export function testSerializeU512_2BytesWide(): bool {
    let truth = hex2bin("020004");
    let num = U512.fromBytes(truth);
    assert(num.getValue() === <U64>1024);
    const bytes = num.toBytes();
    return checkArraysEqual(bytes, typedToArray(truth));
};

export function testSerializeU512_1BytesWide(): bool {
    let truth = hex2bin("0101");
    let num = U512.fromBytes(truth);
    assert(num.getValue() === <U64>1);
    const bytes = num.toBytes();
    return checkArraysEqual(bytes, typedToArray(truth));
};

export function testSerialize100mTimes10(): bool {

    let source = hex2bin("00ca9a3b00000000");
    let maybeVal = fromBytesU64(source);
    assert(maybeVal !== null);
    let val = <U64>maybeVal;

    let valU512 = new U512(val);

    let truth = hex2bin("0400ca9a3b");
    let bytes = valU512.toBytes();
    assert(bytes !== null)
    assert(checkArraysEqual(bytes, typedToArray(truth)));
    return valU512.getValue() === <U64>(100000000*10);
}

export function testBigNum512(): bool {
    let a = new BigNum(16, <u64>18446744073709551614);
    let b = new BigNum(16, 1);

    assert(a.bytes[0] == 0xfffffffe);
    assert(a.bytes[1] == 0xffffffff);
    assert(a.bytes[2] == 0);

    a.add(b);
    assert(a.bytes[0] == 0xffffffff);
    assert(a.bytes[1] == 0xffffffff);
    assert(a.bytes[2] == 0);

    return true;
}
