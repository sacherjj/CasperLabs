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

export function fromBytesU32(bytes: Uint8Array): U32 | null {
    if (bytes.length < 4) {
        return null;
    }
    // NOTE: For whatever reason << and | doesn't produce unsigned integers, so I turned `a << N` into `a * (1<<N)` and bitshift or `|` into addition.
    const number = bytes[0] +
        (bytes[1] * (1 << 8)) +
        (bytes[2] * (1 << 16)) +
        (bytes[3] * (1 << 24));
    return <U32>number;
}

export function toBytesU64(num: u64): u8[] {
    // NOTE: Overflows on unit tests for ranges >= 2**32
    return [
        <u8>(num &  0x00000000000000ff),
        <u8>((num & 0x000000000000ff00) >> 8) & 255,
        <u8>((num & 0x0000000000ff0000) >> 16) & 255,
        <u8>((num & 0x00000000ff000000) >> 24) & 255,
        <u8>((num & 0x000000ff00000000) >> 32) & 255,
        <u8>((num & 0x0000ff0000000000) >> 40) & 255,
        <u8>((num & 0x00ff000000000000) >> 48) & 255,
        <u8>((num & 0xff00000000000000) >> 56) & 255,
    ];
}

export function fromBytesU64(bytes: Uint8Array): U64 | null {
    if (bytes.length < 8) {
        return null;
    }

    // NOTE: Overflows on unit tests for ranges >= 2**32
    const number = bytes[0] +
        (bytes[1] * (1 << 8)) +
        (bytes[2] * (1 << 16)) +
        (bytes[3] * (1 << 24)) +
        (bytes[4] * (1 << 32)) +
        (bytes[5] * (1 << 40)) +
        (bytes[6] * (1 << 48)) +
        (bytes[7] * (1 << 56));
    return <U64>number;
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

export function fromBytesString(s: Uint8Array): String | null {
    var len = fromBytesU32(s);
    if (len === null) {
        return null;
    }
    if (<u32>len < <u32>s.length - 4) {
        return null;
    }
    var result = "";
    for (var i = 4; i < s.length; i++) {
        result += String.fromCharCode(s[i]);
    }
    return result;
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
