import "assemblyscript/std/portable";
import {hex2bin} from "./utils";
import {U512} from "../assembly/bignum";

import test from "ava";

test('serialize u512 4 bytes wide', t => {
    let truth = hex2bin("04efbeadde");
    let num = U512.fromBytes(truth);
    t.deepEqual(num.getValue(), 0xdeadbeef);
   
    var bytes = num.toBytes();
    t.deepEqual(Array.from(bytes), Array.from(truth));
});

test('serialize u512 3 bytes wide', t => {
    let truth = hex2bin("03807801");
    let num = U512.fromBytes(truth);
    t.deepEqual(num.getValue(), 96384);

    var bytes = num.toBytes();
    t.deepEqual(Array.from(bytes), Array.from(truth));
});

test('serialize u512 2 bytes wide', t => {
    let truth = hex2bin("020004");
    let num = U512.fromBytes(truth);
    t.deepEqual(num.getValue(), 1024);

    var bytes = num.toBytes();
    t.deepEqual(Array.from(bytes), Array.from(truth));
});

test('serialize u512 1 byte wide', t => {
    let truth = hex2bin("0101");
    let num = U512.fromBytes(truth);
    t.deepEqual(num.getValue(), 1);

    var bytes = num.toBytes();
    t.deepEqual(Array.from(bytes), Array.from(truth));
});
