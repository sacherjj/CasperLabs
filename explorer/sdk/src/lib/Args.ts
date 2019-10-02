import { Deploy } from 'casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb';
import { BigInt } from 'casperlabs-grpc/io/casperlabs/casper/consensus/state_pb';
import { ByteArray } from '../index';

// Functions to convert data to protobuf Deploy.Arg

type ToValue<T> = (x: T) => Deploy.Arg.Value;

function toValue<T>(set: (value: Deploy.Arg.Value, x: T) => void): ToValue<T> {
  return (x: T) => {
    const value = new Deploy.Arg.Value();
    set(value, x);
    return value;
  };
}

export const BytesValue = toValue<ByteArray>((value, x) =>
  value.setBytesValue(x)
);
export const LongValue = toValue<bigint>((value, x) =>
  value.setLongValue(Number(x))
);
export const BigIntValue = toValue<bigint>((value, x) => {
  const bi = new BigInt();
  bi.setBitWidth(512);
  bi.setValue(x.toString());
  value.setBigInt(bi);
  return value;
});

export function Args(...args: Array<[string, Deploy.Arg.Value]>): Deploy.Arg[] {
  return args.map(x => {
    const [name, value] = x;
    const arg = new Deploy.Arg();
    arg.setName(name);
    arg.setValue(value);
    return arg;
  });
}
