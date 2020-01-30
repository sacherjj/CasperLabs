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

export function testBigNum512Arith(): bool {
    let a = new BigNum(64);
    a.setU64(<u64>18446744073709551614); // 2^64-2
    assert(a.toString() == "fffffffffffffffe");

    let b = new BigNum(64);
    b.setU64(1);

    assert(b.toString() == "1");

    a += b; // a==2^64-1
    assert(a.toString() == "ffffffffffffffff");

    a += b; // a==2^64
    assert(a.toString() == "10000000000000000");

    a -= b; // a==2^64-1
    assert(a.toString() == "ffffffffffffffff");

    a -= b; // a==2^64-2
    assert(a.toString() == "fffffffffffffffe");

    return true;
}

export function testBigNum512Mul(): bool {
    let u64Max = new BigNum(64);
    u64Max.setU64(<u64>18446744073709551615); // 2^64-1
    assert(u64Max.toString() == "ffffffffffffffff");

    let a = new BigNum(64);
    a.setU64(<u64>18446744073709551615);

    assert(a.toString() == "ffffffffffffffff");

    a *= u64Max; // (2^64-1) ^ 2
    assert(a.toString() == "fffffffffffffffe0000000000000001");

    a *= u64Max; // (2^64-1) ^ 3
    assert(a.toString(), "fffffffffffffffd0000000000000002ffffffffffffffff");

    a *= u64Max; // (2^64-1) ^ 4
    assert(a.toString() == "fffffffffffffffc0000000000000005fffffffffffffffc0000000000000001");

    a *= u64Max; // (2^64-1) ^ 5
    assert(a.toString() == "fffffffffffffffb0000000000000009fffffffffffffff60000000000000004ffffffffffffffff");

    a *= u64Max; // (2^64-1) ^ 6
    assert(a.toString() == "fffffffffffffffa000000000000000effffffffffffffec000000000000000efffffffffffffffa0000000000000001");
    a *= u64Max; // (2^64-1) ^ 7
    assert(a.toString() == "fffffffffffffff90000000000000014ffffffffffffffdd0000000000000022ffffffffffffffeb0000000000000006ffffffffffffffff");

    a *= u64Max; // (2^64-1) ^ 8
    assert(a.toString() == "fffffffffffffff8000000000000001bffffffffffffffc80000000000000045ffffffffffffffc8000000000000001bfffffffffffffff80000000000000001");

    return true;
}

export function testBigNumZero(): bool {
    let zero = new BigNum(64);
    assert(zero.toString() == "0");
    return zero.isZero();
}

export function testBigNonZero(): bool {
    let nonzero = new BigNum(64);
    nonzero.setU64(<u64>0xffffffff);
    assert(nonzero.toString() == "ffffffff");
    return !nonzero.isZero();
}

export function testBigNumSetHex(): bool {
    let large = new BigNum(64);
    large.setHex("fffffffffffffff8000000000000001bffffffffffffffc80000000000000045ffffffffffffffc8000000000000001bfffffffffffffff80000000000000001");
    assert(large.toString() == "fffffffffffffff8000000000000001bffffffffffffffc80000000000000045ffffffffffffffc8000000000000001bfffffffffffffff80000000000000001");
    return true;
}
