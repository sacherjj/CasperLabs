import * as externals from "./externals";
import {URef, AccessRights} from "./uref";
import {Error, ErrorCode} from "./error";
import {CLValue} from "./clvalue";
import {Key, PublicKey} from "./key";
import {toBytesString,
        toBytesVecT,
        fromBytesMap,
        fromBytesString} from "./bytesrepr";
import {U512} from "./bignum";
import {UREF_SERIALIZED_LENGTH, KEY_UREF_SERIALIZED_LENGTH} from "./constants";
import {Pair} from "./pair";

// NOTE: interfaces aren't supported in AS yet: https://github.com/AssemblyScript/assemblyscript/issues/146#issuecomment-399130960
// interface ToBytes {
//   fromBytes(bytes: Uint8Array): ToBytes;
// }

const ADDR_LENGTH = 32;

export const enum SystemContract {
  Mint = 0,
  ProofOfStake = 1,
  StandardPayment = 2,
}

export function getArgSize(i: u32): U32 | null {
  // TODO: Docs aren't clear on pointers, but perhaps `var size = <u32>0; changetype<usize>(size);` might take a pointer of a value we could pass
  let size = new Array<u32>(1);
  size[0] = 0;

  let ret = externals.get_arg_size(i, size.dataStart);
  if (ret > 0) {
    return null;
  }
  return changetype<U32>(size[0]);
}

export function getArg(i: u32): Uint8Array | null {
  let arg_size = getArgSize(i);
  if (arg_size === null) {
    return null;
  }
  let arg_size_u32 = changetype<u32>(arg_size);
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
  let decodeResult = URef.fromBytes(data);
  if (decodeResult.hasError()) {
    return null;
  }
  return decodeResult.value;
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
  return Key.fromURef(uref);
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
  let argBytes = toBytesVecT(args);

  let resultSize = new Uint32Array(1);
  resultSize.fill(0);

  let ret = externals.call_contract(
      <usize>keyBytes.dataStart,
      keyBytes.length,
      argBytes.dataStart,
      argBytes.length,
      resultSize.dataStart,
  );
  if (ret > 0) {
    return null;
  }

  let hostBufSize = resultSize[0];
  if (hostBufSize > 0) {
    return readHostBuffer(hostBufSize);
  } else {
    return new Uint8Array(0);
  }
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
  let keyBytes = new Uint8Array(KEY_UREF_SERIALIZED_LENGTH);
  let resultSize = new Uint32Array(1);
  let ret =  externals.get_key(
      nameBytes.dataStart,
      nameBytes.length,
      keyBytes.dataStart,
      keyBytes.length,
      resultSize.dataStart,
  );
  const error = Error.fromResult(ret);
  if (error !== null) {
    error.revert();
    return null;
  }
  let key = Key.fromBytes(keyBytes.slice(0, <i32>resultSize[0])); // total guess
  return key.ok();
}

export function ret(value: CLValue): void {
  const valueBytes = value.toBytes();
  externals.ret(
    valueBytes.dataStart,
    valueBytes.length
  );
  unreachable();
}

export function hasKey(name: String): bool {
  const nameBytes = toBytesString(name);
  let ret = externals.has_key(nameBytes.dataStart, nameBytes.length);
  return ret == 0;
}

export function getBlockTime(): u64 {
  let bytes = new Uint64Array(1);
  externals.get_blocktime(bytes.dataStart);
  return <u64>bytes[0];
}

export function getCaller(): PublicKey {
  let outputSize = new Uint32Array(1);
  let ret = externals.get_caller(outputSize.dataStart);
  const error = Error.fromResult(ret);
  if (error != null) {
    error.revert();
    return <PublicKey>unreachable();
  }
  const publicKeyBytes = readHostBuffer(outputSize[0]);
  if (publicKeyBytes === null) {
    Error.fromErrorCode(ErrorCode.Deserialize).revert();
    return <PublicKey>unreachable();
  }
  const publicKeyResult = PublicKey.fromBytes(publicKeyBytes);
  if (publicKeyResult.hasError()) {
    Error.fromErrorCode(ErrorCode.Deserialize).revert();
    return <PublicKey>unreachable();
  }
  return publicKeyResult.value;
}

export enum Phase {
  System = 0,
  Payment = 1,
  Session = 2,
  FinalizePayment = 3,
}

export function getPhase(): Phase {
  let bytes = new Uint8Array(1);
  externals.get_phase(bytes.dataStart);
  const phase = bytes[0];
  return <Phase>phase;
}

export function removeKey(name: String): void{
  var nameBytes = toBytesString(name);
  externals.remove_key(nameBytes.dataStart, nameBytes.length);
}

export function listNamedKeys(): Array<Pair<String, Key>> {
  let totalKeys = new Uint32Array(1);
  let resultSize = new Uint32Array(1);

  const res = externals.load_named_keys(totalKeys.dataStart, resultSize.dataStart);
  const error = Error.fromResult(res);
  if (error != null) {
    error.revert();
    return <Array<Pair<String, Key>>>unreachable();
  }

  if (totalKeys[0] == 0) {
    return new Array<Pair<String, Key>>();
  }

  let mapBytes = readHostBuffer(resultSize[0]);
  if (mapBytes === null) {
    Error.fromErrorCode(ErrorCode.HostBufferEmpty).revert();
    return <Array<Pair<String, Key>>>unreachable();
  }
  let maybeMap = fromBytesMap<String, Key>(
    mapBytes,
    fromBytesString,
    Key.fromBytes);

  if (maybeMap.hasError()) {
    Error.fromErrorCode(ErrorCode.Deserialize).revert();
    return <Array<Pair<String, Key>>>unreachable();
  }
  return maybeMap.value;
}

export function upgradeContractAtURef(name: String, uref: URef): void {
  const nameBytes = toBytesString(name);
  const key = Key.fromURef(uref);
  const keyBytes = key.toBytes();
  let ret = externals.upgrade_contract_at_uref(
      nameBytes.dataStart,
      nameBytes.length,
      keyBytes.dataStart,
      keyBytes.length
  );
  if (ret < 1)
    return;
  const error = Error.fromResult(ret);
  if(error !== null)
    error.revert();
}
