
// Functions to convert data to the FFI

// it's size ++ bytes
// It's `[u8; 32]` (32 element byte array) but serializes to `(32.toBytes() ++ array.toBytes())`
// We serialize 32(literally, number 32) to 4 bytes instead of 1 byte, little endiannes.
// This is how`111..11` public key looks like when serialized:
// [32, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]
export function PublicKey(bytes: ByteArray): ByteArray {
  const size = Buffer.alloc(4);
  size.writeInt32LE(bytes.length, 0);
  return Buffer.concat([size, bytes]);
}
