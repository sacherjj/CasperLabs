// This is a copy from server/lib/Serialization.ts because symlinks didn't compile.
// Need to figure out how to share code.

type Serializer<T> = (arg: T) => ByteArray;

const Size: Serializer<number> = size => {
  const buffer = Buffer.alloc(4);
  buffer.writeInt32LE(size, 0);
  return buffer;
};

export const ByteArrayArg: Serializer<ByteArray> = bytes => {
  const components = [Size(bytes.length), bytes];
  return Buffer.concat(components.map(x => Buffer.from(x)));
};
