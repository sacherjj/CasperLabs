import "assemblyscript/std/portable";
import {URef, decodeOptional} from "../assembly";

import test from "ava";
import {hex2bin} from "./utils";

test('decoded optional is some', t => {
    var optionalSome = new Uint8Array(10);
    for (var i = 0; i < 10; i++) {
        optionalSome[i] = i + 1;
    }
    var res = decodeOptional(optionalSome);
    t.not(res, null);
    t.deepEqual(Array.from(res), [2, 3, 4, 5, 6, 7, 8, 9, 10]);
});

test('decoded optional is none', t => {
    var optionalSome = new Uint8Array(10);
    optionalSome[0] = 0;
    var res = decodeOptional(optionalSome);
    t.is(res, null);
});

test("decode uref from bytes with access rights", t => {
    var truth = hex2bin("2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a0107");
    var uref = URef.fromBytes(truth);
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
    var truth = hex2bin("2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a00");
    var uref = URef.fromBytes(truth);
    t.not(uref, null);
    t.deepEqual(Array.from(uref.getBytes()), [
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
        42, 42,
    ]);
    t.is(uref.getAccessRights(), null);
})
