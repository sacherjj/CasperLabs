import { Deploy } from "../grpc/io/casperlabs/casper/consensus/consensus_pb";

// Functions to convert data to protobuf Deploy.Arg

type ToValue<T> = (x: T) => Deploy.Arg.Value;

function toValue<T>(set: (value: Deploy.Arg.Value, x: T) => void): ToValue<T> {
  return (x: T) => {
    const value = new Deploy.Arg.Value();
    set(value, x);
    return value;
  };
}

export const BytesValue = toValue<ByteArray>((value, x) => value.setBytesValue(x));
export const LongValue = toValue<bigint>((value, x) => value.setLongValue(Number(x)));

export function Args(...args: Array<[string, Deploy.Arg.Value]>): Deploy.Arg[] {
  return args.map((x) => {
    const [name, value] = x;
    const arg = new Deploy.Arg();
    arg.setName(name);
    arg.setValue(value);
    return arg;
  });
}
