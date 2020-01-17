import { hex2bin, isArraysEqual } from "../utils/helpers";
import { typedToArray } from "../../assembly/utils";

export function testHex2Bin(): bool {
    let truth = hex2bin("deadbeef");
    return isArraysEqual(typedToArray(truth), [222, 173, 190, 239]);
}

export function testIsArraysEqual(): bool {
    assert(isArraysEqual(<u8[]>[], []));
    assert(!isArraysEqual(<u8[]>[1, 2, 3], [1]));
    return isArraysEqual(<u8[]>[1, 2, 3], [1, 2, 3]);
}
