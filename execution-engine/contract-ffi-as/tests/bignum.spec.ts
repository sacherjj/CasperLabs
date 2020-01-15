import "assemblyscript/std/portable";
import {hex2bin} from "./utils";
import {U512} from "../assembly/bignum";
import {fromBytesU64} from "../assembly/bytesrepr";

import test from "ava";

test.skip('serialize u512 4 bytes wide', t => {
    let truth = hex2bin("04efbeadde");
    let num = U512.fromBytes(truth);
    t.deepEqual(num.getValue(), 0xdeadbeef);

    var bytes = num.toBytes();
    t.deepEqual(Array.from(bytes), Array.from(truth));
});

test.skip('serialize u512 3 bytes wide', t => {
    let truth = hex2bin("03807801");
    let num = U512.fromBytes(truth);
    t.deepEqual(num.getValue(), 96384);

    var bytes = num.toBytes();
    t.deepEqual(Array.from(bytes), Array.from(truth));
});

test.skip('serialize u512 2 bytes wide', t => {
    let truth = hex2bin("020004");
    let num = U512.fromBytes(truth);
    t.deepEqual(num.getValue(), 1024);

    var bytes = num.toBytes();
    t.deepEqual(Array.from(bytes), Array.from(truth));
});

test.skip('serialize u512 1 byte wide', t => {
    let truth = hex2bin("0101");
    let num = U512.fromBytes(truth);
    t.deepEqual(num.getValue(), 1);

    var bytes = num.toBytes();
    t.deepEqual(Array.from(bytes), Array.from(truth));
});

test.skip('serialize 100m times 10', t => {

    let source = hex2bin("00ca9a3b00000000");
    let val = fromBytesU64(source);
    t.not(val, null);

    let u512_val = new U512(val);

    let truth = hex2bin("0400ca9a3b");
    let bytes = u512_val.toBytes();
    t.not(bytes, null);
    t.deepEqual(Array.from(bytes), Array.from(bytes));
    t.deepEqual(u512_val.getValue(), 100000000*10);
})
