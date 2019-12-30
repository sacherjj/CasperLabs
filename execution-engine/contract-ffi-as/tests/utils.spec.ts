import test from "ava";
import {hex2bin} from "./utils";

test("hex2bin", t => {
    t.deepEqual(Array.from(hex2bin("deadbeef")), [222, 173, 190, 239]);
});
