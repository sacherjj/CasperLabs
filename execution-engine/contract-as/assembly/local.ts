//@ts-nocheck
import * as externals from "./externals";
import {Error, ErrorCode} from "./error";
import {CLValue} from "./clvalue";
import {readHostBuffer} from "./index";

export function readLocal(local: Uint8Array): Uint8Array | null {
    let valueSize = new Uint8Array(1);
    const ret = externals.read_value_local(local.dataStart, local.length, valueSize.dataStart);
    if (ret == ErrorCode.ValueNotFound){
        return null;
    }
    const error = Error.fromResult(ret);
    if (error != null) {
        error.revert();
        return null;
    }
    return readHostBuffer(valueSize[0]);
}

export function writeLocal(local: Uint8Array, value: CLValue): void {
    const valueBytes = value.toBytes();
    externals.write_local(
        local.dataStart,
        local.length,
        valueBytes.dataStart,
        valueBytes.length
    );
}

export function addLocal(local: Uint8Array, value: CLValue): void {
    const valueBytes = value.toBytes();
    externals.add_local(
        local.dataStart,
        local.length,
        valueBytes.dataStart,
        valueBytes.length
    );
}
