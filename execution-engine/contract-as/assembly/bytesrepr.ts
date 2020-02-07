import { Pair } from "./pair";

export enum Error {
    // Last operation was a success
    Ok = 0,
    // Early end of stream
    EarlyEndOfStream = 1,
    // Unexpected data encountered while decoding byte stream
    FormattingError = 2,
}

// NOTE: Using Error.Ok generates additional "start" node with initializing code which we want to avoid
// Ref: https://github.com/AssemblyScript/assemblyscript/issues/566#issuecomment-581835994
@lazy
let lastError: i32 = <Error>0;

export function GetLastError(): i32 {
    return lastError;
}

export function SetLastError(error: i32): void {
    lastError = error;
}

// Any fromBytes operation sets this, so caller can know how much bytes to
// skip in the input stream for complex types
@lazy
let LastDecodedBytesCount: u32 = 0;

export function SetDecodedBytesCount(value: u32): void {
    LastDecodedBytesCount = value;
}

export function AddDecodedBytesCount(value: u32): void {
    LastDecodedBytesCount += value;
}

export function GetDecodedBytesCount(): u32 {
    return LastDecodedBytesCount;
}

export function toBytesU8(num: u8): u8[] {
    return [num];
}

export function fromBytesU8(bytes: Uint8Array): u8 {
    if (bytes.length < 1) {
        SetLastError(Error.EarlyEndOfStream);
        return 0;
    }
    SetLastError(Error.Ok);
    return load<u8>(bytes.dataStart);
}

// Converts u32 to little endian
export function toBytesU32(num: u32): u8[] {
    let bytes = new Uint8Array(4);
    store<u32>(bytes.dataStart, num);
    let result = new Array<u8>(4);
    for (var i = 0; i < 4; i++) {
        result[i] = bytes[i];
    }
    return result;
}

export function fromBytesU32(bytes: Uint8Array): u32 {
    if (bytes.length < 4) {
        SetLastError(Error.EarlyEndOfStream);
        return 0;
    }
    const number = <u32>load<u32>(bytes.dataStart);

    SetDecodedBytesCount(4);

    SetLastError(Error.Ok);
    return number;
}

export function toBytesI32(num: i32): u8[] {
    let bytes = new Uint8Array(4);
    store<i32>(bytes.dataStart, num);
    let result = new Array<u8>(4);
    for (var i = 0; i < 4; i++) {
        result[i] = bytes[i];
    }
    return result;
}

export function fromBytesI32(bytes: Uint8Array): i32 {
    if (bytes.length < 4) {
        SetLastError(Error.EarlyEndOfStream);
        return 0;
    }
    SetDecodedBytesCount(4);
    SetLastError(Error.Ok);
    return load<i32>(bytes.dataStart);
}

export function toBytesU64(num: u64): u8[] {
    let bytes = new Uint8Array(8);
    store<u64>(bytes.dataStart, num);
    let result = new Array<u8>(8);
    for (var i = 0; i < 8; i++) {
        result[i] = bytes[i];
    }
    return result;
}

export function fromBytesU64(bytes: Uint8Array): u64 {
    if (bytes.length < 8) {
        SetLastError(Error.EarlyEndOfStream);
        return 0;
    }

    SetDecodedBytesCount(8);

    SetLastError(Error.Ok);
    return load<u64>(bytes.dataStart);
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

export function fromBytesMap<K, V>(
    bytes: Uint8Array,
    decodeKey: (bytes1: Uint8Array) => K | null,
    decodeValue: (bytes2: Uint8Array) => V | null
): Array<Pair<K, V>> | null {
    const length = fromBytesU32(bytes);
    if (GetLastError() != Error.Ok) {
        SetLastError(Error.EarlyEndOfStream);
        return null;
    }

    let result = new Array<Pair<K, V>>();

    if (length == 0) {
        return result;
    }

    let currentDecodedBytes = GetDecodedBytesCount();
    let currentOffset = currentDecodedBytes;

    let bytes = bytes.subarray(currentDecodedBytes);

    for (let i = 0; i < changetype<i32>(length); i++) {
        let key = decodeKey(bytes);
        if (key === null) {
            SetLastError(Error.FormattingError);
            return null;
        }

        let keyDecodedBytes = GetDecodedBytesCount();
        currentOffset += keyDecodedBytes;
        bytes = bytes.subarray(keyDecodedBytes);

        let value = decodeValue(bytes);
        if (value === null) {
            SetLastError(Error.FormattingError);
            return null;
        }

        let valueDecodedBytes = GetDecodedBytesCount()
        currentOffset += valueDecodedBytes;

        bytes = bytes.subarray(valueDecodedBytes);

        let pair = new Pair<K, V>(key, value);
        result.push(pair);
    }

    SetDecodedBytesCount(currentOffset);
    SetLastError(Error.Ok);
    return result;
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
    if (GetLastError() != Error.Ok) {
        SetLastError(Error.EarlyEndOfStream);
        return null;
    }
    let leni32 = changetype<i32>(len);
    if (s.length < leni32 + 4) {
        SetLastError(Error.EarlyEndOfStream);
        return null;
    }
    let currentOffset = GetDecodedBytesCount();
    var result = "";
    for (var i = 0; i < 0 + leni32; i++) {
        result += String.fromCharCode(s[4 + i]);
    }

    SetDecodedBytesCount(currentOffset + leni32);
    SetLastError(Error.Ok);
    return result;
}

export function toBytesArrayU8(arr: Array<u8>): u8[] {
    let bytes = toBytesU32(<u32>arr.length);
    return bytes.concat(arr);
}

export function fromBytesArrayU8(arr: Uint8Array): Uint8Array | null {
    var len = fromBytesU32(arr);
    if (GetLastError() != Error.Ok) {
        SetLastError(Error.EarlyEndOfStream);
        return null;
    }

    let offset = GetDecodedBytesCount();

    if (<u32>len < <u32>arr.length - 4) {
        SetLastError(Error.EarlyEndOfStream);
        return null;
    }
    let currentOffset = offset + len;
    SetDecodedBytesCount(currentOffset);
    SetLastError(Error.Ok);
    return arr.subarray(4, 4 + len);
}

export function toBytesVecT<T>(ts: T[]): Array<u8> {
    let bytes = toBytesU32(<u32>ts.length);
    for (let i = 0; i < ts.length; i++) {
        bytes = bytes.concat(ts[i].toBytes());
    }
    return bytes;
}

export function fromBytesStringList(arr: Uint8Array): String[] | null {
    var len = fromBytesU32(arr);
    if (GetLastError() != Error.Ok) {
        SetLastError(Error.EarlyEndOfStream);
        return null;
    }

    if (len == 0) {
        return [];
    }

    let offset = GetDecodedBytesCount();
    let head = arr.subarray(offset);

    let result: String[] = [];

    for (let i = 0; i < changetype<i32>(len); ++i) {
        let str = fromBytesString(head);
        if (str === null) {
            SetLastError(Error.FormattingError);
            return null;
        }
        offset += GetDecodedBytesCount();

        result.push(str);
        head = head.subarray(4 + str.length);
    }

    SetDecodedBytesCount(offset);
    SetLastError(Error.Ok);
    return result;
}

export function toBytesStringList(arr: String[]): u8[] {
    let data = toBytesU32(arr.length);
    for (let i = 0; i < arr.length; i++) {
        const strBytes = toBytesString(arr[i]);
        data = data.concat(strBytes);
    }
    return data;
}
