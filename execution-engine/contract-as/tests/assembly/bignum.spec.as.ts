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

export function testNeg(): bool {
    // big == -big
    // in 2s compliment: big == ~(~big+1)+1
    let big = new BigNum(64);
    big.setHex("e7ed081ae96850db0c7d5b42094b5e09b0631e6b9f63efe4deb90d7dd677c82f8ce52eccda5b03f5190770a763729ae9ab85c76cd1dc9606ec9dcf2e2528fccb");

    let one = new BigNum(64);
    one.setU64(1);

    assert(big == -(-big));
    return true;
}

export function testComparison(): bool {
    let zero = new BigNum(64);
    assert(zero.isZero());
    let one = new BigNum(64);
    one.setU64(1);

    let u32Max = new BigNum(64);
    u32Max.setU64(4294967295);

    assert(zero != one);
    assert(one == one);
    assert(zero == zero);

    assert(zero < one);
    assert(zero <= one);
    assert(one <= one);

    assert(one > zero);
    assert(one >= zero);
    assert(one >= one);

    // python: large = random.randint(0, 2**512-1)
    let large1 = new BigNum(64);
    large1.setHex("a25bd58358ae4cd57ba0a4afcde6e9aa55c801d88854541dfc6ea5e3c1fada9ed9cb1e48b0a2d553faa26e5381743415ae1ec593dc67fc525d18e0b6fdf3f7ae");

    let large2 = new BigNum(64);
    large2.setHex("f254bb1c7f6654f5ad104854709cb5c09009ccd2b78b5364fefd3a5fa99381a173c5498966e77d88d443bd1a650b4bcb8bb8a92013a85a7095330bc79a2e22dc");

    assert(large1.cmp(large2) != 0);
    assert(large1 != large2);
    assert(large2 == large2);
    assert(large1 == large1);

    assert(large1 < large2);
    assert(large1 <= large2);
    assert(large2 <= large2);

    assert(large2 > large1);
    assert(large2 >= large1);
    assert(large2 >= large2);

    assert(large1 > zero);
    assert(large1 > one);
    assert(large1 > u32Max);
    assert(large2 > u32Max);
    assert(large1 >= u32Max);
    assert(u32Max >= one);
    assert(one <= u32Max);
    assert(one != u32Max);
    return true;
}

export function testBits(): bool {
    let zero = new BigNum(64);
    assert(zero.bits() == 0);
    let one = new BigNum(64);
    one.setU64(1);
    assert(one.bits() == 1);

    let value = new BigNum(64);
    for (let i = 0; i < 63; i++) {
        value.setU64(1 << i);
        assert(value.bits() == i + 1);
    }

    let shl512P1 = new BigNum(64);
    shl512P1.setHex("10000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000");
    assert(shl512P1.bits() == 509);

    let u512Max = new BigNum(64);
    u512Max.setHex("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
    assert(u512Max.bits() == 512);

    let mix = new BigNum(64);
    mix.setHex("55555555555");
    assert(mix.bits() == 43);
    return true;
}
