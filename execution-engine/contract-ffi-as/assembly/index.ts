import * as externals from "./externals";
import {UREF_SERIALIZED_LENGTH, URef, AccessRights} from "./uref";
import {CLValue} from "./clvalue";
import {Key} from "./key";
import {serializeArguments, toBytesString, toBytesArrayU8} from "./bytesrepr";
import {U512} from "./bignum";

// NOTE: interfaces aren't supported in AS yet: https://github.com/AssemblyScript/assemblyscript/issues/146#issuecomment-399130960
// interface ToBytes {
//   fromBytes(bytes: Uint8Array): ToBytes;
// }

const ADDR_LENGTH = 32;

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
  let extraUrefs: CLValue[] = [];
  return callContractExt(key, args, extraUrefs);
}

export function callContractExt(key: Key, args: CLValue[], extraUrefs: CLValue[]): Uint8Array | null {
  let keyBytes = key.toBytes();
  let argBytes = serializeArguments(args);
  let extraURefsBytes = serializeArguments(extraUrefs);

  let resultSize = new Uint32Array(1);
  resultSize.fill(0);

  let ret = externals.call_contract(
      <usize>keyBytes.dataStart,
      keyBytes.length,
      argBytes.dataStart,
      argBytes.length,
      extraURefsBytes.dataStart,
      extraURefsBytes.length,
      resultSize.dataStart,
  );
  if (ret > 0) {
    return null;
  }

  let hostBufSize = resultSize[0];
  return readHostBuffer(hostBufSize);
}

export function putKey(name: String, key: Key): void {
  var nameBytes = toBytesString(name);
  var keyBytes = key.toBytes();
  externals.put_key(
    nameBytes.dataStart,
    nameBytes.length,
    keyBytes.dataStart,
    keyBytes.length
  );
}

export function getKey(name: String): Key | null {
  var nameBytes = toBytesString(name);
  let keyBytes = new Uint8Array(UREF_SERIALIZED_LENGTH); // TODO: some equivalent of Key::serialized_size_hint() ?
  //let resultSize = new Uint32Array(1);
  let resultSize: usize = 0;
  let ret =  externals.get_key(
      nameBytes.dataStart,
      nameBytes.length,
      keyBytes.dataStart,
      keyBytes.length,
      resultSize.dataStart,
  );
  if (ret == 0) {
    return null;
  }
  let key = Key.fromBytes(keyBytes.slice(0, resultSize)); // total guess
  return key;
}

export enum TransferredTo {
  ExistingAccount = 0,
  NewAccount = 1,
}

export function transferToAccount(target: Uint8Array, amount: U512): U32 | null {
  // var targetBytes = (target);
  let amountBytes = amount.toBytes();

  let ret = externals.transfer_to_account(
      target.dataStart,
      target.length,
      amountBytes.dataStart,
      amountBytes.length,
  );

  if (ret <= 1) {
    return <U32>ret;
  }
  else {
    return null;
  }
}
