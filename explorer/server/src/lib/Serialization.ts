
// Functions to convert data to the FFI

type Serializer<T> = (arg: T) => ByteArray;

const Size: Serializer<number> = (size) => {
  const buffer = Buffer.alloc(4);
  buffer.writeInt32LE(size, 0);
  return buffer;
};

// `Array[Byte]` serializes as follows:
// 1) length of the array as 4 bytes
// 2) your array of bytes
//
// So for `[1,2,3,4,5,6]` it serializes to`[6, 0, 0, 0, 1, 2, 3, 4, 5, 6]`
export const ByteArrayArg: Serializer<ByteArray> = (bytes) => {
  return Buffer.concat([Size(bytes.length), bytes]);
};

// A public key is the same as array but its' expected to be 32 bytes long exactly.
// It's `[u8; 32]` (32 element byte array) but serializes to `(32.toBytes() ++ array.toBytes())`
// We serialize 32(literally, number 32) to 4 bytes instead of 1 byte, little endiannes.
// This is how`111..11` public key looks like when serialized:
// [32, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]
export const PublicKeyArg = ByteArrayArg;

export const UInt64Arg: Serializer<bigint> = (value) => {
  const u64Buffer = Buffer.alloc(8);
  u64Buffer.writeBigUInt64LE(value, 0);
  return u64Buffer;
};

/** Combine multiple arguments. */
export function Args(...args: ByteArray[]): ByteArray {
  return Buffer.concat([Size(args.length), ...args]);
}
