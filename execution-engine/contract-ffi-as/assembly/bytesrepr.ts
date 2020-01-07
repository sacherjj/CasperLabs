import {CLValue} from "./clvalue";

export function toBytesU32(num: u32): u8[] {
    // Converts u32 to little endian
    // NOTE: AS apparently has store<i32> which could work for us but looks like AS portable stdlib doesn't provide it
    return [
        <u8>(num & 0x000000ff),
        <u8>((num & 0x0000ff00) >> 8) & 255,
        <u8>((num & 0x00ff0000) >> 16) & 255,
        <u8>((num & 0xff000000) >> 24) & 255,
    ];
}

export function toBytesPair(key: u8[], value: u8[]): u8[] {
    return key.concat(value);
}

export function toBytesMap(pairs: u8[][]): u8[] {
    // https://github.com/AssemblyScript/docs/blob/master/standard-library/map.md#methods
    // Gets the keys contained in this map as an array, in insertion order. This is preliminary while iterators are not supported.
    // See https://github.com/AssemblyScript/assemblyscript/issues/166
    var bytes = toBytesU32(<u32>pairs.length);
    for (var i = 0; i < pairs.length; i++) {
        var pairBytes = pairs[i];
        bytes = bytes.concat(pairBytes);
    }
    return bytes;
}

export function toBytesString(s: String): u8[] {
    let bytes = toBytesU32(<u32>s.length);
    for (let i = 0; i < s.length; i++) {
        let charCode = s.charCodeAt(i);
        // Assumes ascii encoding (i.e. charCode < 0x80)
        bytes.push(<u8>charCode);
    }
    return bytes;
}

export function toBytesArrayU8(arr: Array<u8>): u8[] {
    let bytes = toBytesU32(<u32>arr.length);
    return bytes.concat(arr);
}

export function serializeArguments(values: CLValue[]): Array<u8> {
    let bytes = toBytesU32(<u32>values.length);
    for (let i = 0; i < values.length; i++) {
        bytes = bytes.concat(values[i].toBytes());
    }
    return bytes;
}
