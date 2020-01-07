// The entry file of your WebAssembly module.
import * as externals from "./externals";
import { UREF_SERIALIZED_LENGTH , URef} from "./uref";
import {CLValue} from "./clvalue";
import {Key} from "./key";

// NOTE: interfaces aren't supported in AS yet: https://github.com/AssemblyScript/assemblyscript/issues/146#issuecomment-399130960
// interface ToBytes {
//   fromBytes(bytes: Uint8Array): ToBytes;
// }

const ADDR_LENGTH = 32;
const PURSE_ID_SERIALIZED_LENGTH = UREF_SERIALIZED_LENGTH;

export const enum SystemContract {
  Mint = 0,
  ProofOfStake = 1,
}

export function getArgSize(i: u32): U32 | null {
  // TODO: Docs aren't clear on pointers, but perhaps `var size = <u32>0; changetype<usize>(size);` might take a pointer of a value we could pass
  let size = new Array<u32>(1);
  size[0] = 0;

  let ret = externals.get_arg_size(i, size.dataStart);
  if (ret > 0) {
    return null;
  }
  return <U32>size[0];
}

export function getArg(i: u32): Uint8Array | null {
  let arg_size = getArgSize(i);
  if (arg_size == null) {
    return null;
  }
  let arg_size_u32 = <u32>(arg_size);
  let data = new Uint8Array(arg_size_u32);
  let ret = externals.get_arg(i, data.dataStart, arg_size_u32);
  if (ret > 0) {
    // TODO: Error handling with standarized errors enum
    return null;
  }
  return data;
}

// export class Hash{
//
// }

export function getMainPurse(): URef | null {
  let data = new Uint8Array(PURSE_ID_SERIALIZED_LENGTH);
  data.fill(0);
  externals.get_main_purse(data.dataStart);
  return URef.fromBytes(data);
}

export function getSystemContract(system_contract: SystemContract): URef | null {
  let data = new Uint8Array(UREF_SERIALIZED_LENGTH);
  let ret = externals.get_system_contract(<u32>system_contract, data.dataStart, data.length);
  if (ret > 0) {
    // TODO: revert
    return null;
  }
  return URef.fromBytes(data);
}


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
  var result = toBytesU32(<u32>pairs.length);
  for (var i = 0; i < pairs.length; i++) {
    var pairBytes = pairs[i];
    result = result.concat(pairBytes);
  }
  return result;
}

export function toBytesString(s: String): u8[] {
  let prefix = toBytesU32(<u32>s.length);
  for (let i = 0; i < s.length; i++) {
    let charCode = s.charCodeAt(i);
    // Assumes ascii encoding (i.e. charCode < 0x80)
    prefix.push(<u8>charCode);
  }
  return prefix;
}

export function toBytesArrayU8(arr: Array<u8>): u8[] {
  let prefix = toBytesU32(<u32>arr.length);
  return prefix.concat(arr);
}

export function serializeArguments(values: CLValue[]): Array<u8> {
  let prefix = toBytesU32(<u32>values.length);
  for (let i = 0; i < values.length; i++) {
    prefix = prefix.concat(values[i].toBytes());
  }
  return prefix;
}

export function storeFunctionAtHash(name: String, namedKeysBytes: u8[]): Key | null {
  var nameBytes = toBytesString(name);
  var addr = new Uint8Array(ADDR_LENGTH);
  externals.store_function_at_hash(
      <usize>nameBytes.dataStart,
      nameBytes.length,
      <usize>namedKeysBytes.dataStart,
      namedKeysBytes.length,
      <usize>addr.dataStart
  );
  return Key.fromHash(addr);
}

export function callContract(key: Key, args: CLValue[]): Uint8Array | null {
  let keyBytes = key.toBytes();
  let argBytes = serializeArguments(args);
  let extraURefs = serializeArguments([]);

  let resultSize = new Uint32Array(1);
  resultSize.fill(0);

  let ret = externals.call_contract(
    <usize>keyBytes.dataStart,
    keyBytes.length,
    argBytes.dataStart,
    argBytes.length,
    extraURefs.dataStart,
    extraURefs.length,
    resultSize.dataStart,
  );
  if (ret > 0) {
    return null;
  }

  let hostBufSize = resultSize[0];
  return readHostBuffer(hostBufSize);
}

export function readHostBuffer(count: u32): Uint8Array | null {
  let result = new Uint8Array(count);
  let resultSize = new Uint32Array(1);

  let ret = externals.read_host_buffer(result.dataStart, result.length, resultSize.dataStart);
  if (ret > 0) {
    return null;
  }
  return result;
}

export function transferFromPurseToPurse(source: URef, target: URef, amount: Uint8Array): i32 {
  let sourceBytes = source.toBytes();
  let targetBytes = target.toBytes();

  let ret = externals.transfer_from_purse_to_purse(
    sourceBytes.dataStart,
    sourceBytes.length,
    targetBytes.dataStart,
    targetBytes.length,
    // NOTE: amount has U512 type but is not deserialized throughout the execution, as there's no direct replacement for big ints
    amount.dataStart,
    amount.length,
  );
  return ret;
}
