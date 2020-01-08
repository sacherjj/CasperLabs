import * as externals from "./externals";
import {UREF_SERIALIZED_LENGTH, URef, AccessRights} from "./uref";
import {CLValue} from "./clvalue";
import {Key} from "./key";
import {serializeArguments, toBytesString} from "./bytesrepr";

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

export function readHostBuffer(count: u32): Uint8Array | null {
  let result = new Uint8Array(count);
  let resultSize = new Uint32Array(1);

  let ret = externals.read_host_buffer(result.dataStart, result.length, resultSize.dataStart);
  if (ret > 0) {
    return null;
  }
  return result;
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

export function storeFunction(name: String, namedKeysBytes: u8[]): Key {
  var nameBytes = toBytesString(name);
  var addr = new Uint8Array(ADDR_LENGTH);
  externals.store_function(
      <usize>nameBytes.dataStart,
      nameBytes.length,
      <usize>namedKeysBytes.dataStart,
      namedKeysBytes.length,
      <usize>addr.dataStart
  );
  let uref = new URef(addr, AccessRights.READ_ADD_WRITE);
  return uref.asKey();
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

export function getMainPurse(): URef | null {
  let data = new Uint8Array(PURSE_ID_SERIALIZED_LENGTH);
  data.fill(0);
  externals.get_main_purse(data.dataStart);
  return URef.fromBytes(data);
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

export function putKey(name: String, key: Key): void {
  var nameBytes = toBytesString(name);
  var keyBytes = key.toBytes();
  externals.put_key(
    nameBytes.dataStart,
    nameBytes.length,
    keyBytes.dataStart,
    keyBytes.length);
}
