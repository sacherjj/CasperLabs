// The entry file of your WebAssembly module.
import * as externals from "./externals";

function getArgSize(i: u32): U32 | null {
  // TODO: Docs aren't clear on pointers, but perhaps `var size = <u32>0; changetype<usize>(size);` might take a pointer of a value we could pass
  var size = new Array<u32>(1);
  size[0] = 0;

  var ret = externals.get_arg_size(i, size.dataStart);
  if (ret > 0) {
    return null;
  }
  return <U32>size[0];
}

function getArg(i: u32): Uint8Array | null {
  var arg_size = getArgSize(i);
  if (arg_size == null) {
    return null;
  }
  var arg_size_u32 = <u32>(arg_size);
  var data = new Uint8Array(arg_size_u32);
  var ret = externals.get_arg(i, data.dataStart, arg_size_u32);
  if (ret > 0) {
    // TODO: Error handling with standarized errors enum
    return null;
  }
  return data;
}

const OPTION_TAG_SERIALIZED_LENGTH = 1;
const ACCESS_RIGHTS_SERIALIZED_LENGTH = 1;
const UREF_ADDR_LENGTH = 32;
const UREF_SERIALIZED_LENGTH = UREF_ADDR_LENGTH + OPTION_TAG_SERIALIZED_LENGTH + ACCESS_RIGHTS_SERIALIZED_LENGTH;
const PURSE_ID_SERIALIZED_LENGTH = UREF_SERIALIZED_LENGTH;


// NOTE: interfaces aren't supported in AS yet: https://github.com/AssemblyScript/assemblyscript/issues/146#issuecomment-399130960
// interface ToBytes {
//   fromBytes(bytes: Uint8Array): ToBytes;
// }

export class URef {
  private bytes: Uint8Array;
  private accessRights: U8 | null = null; // NOTE: Optional access rights are currently marked as "null"

  constructor(bytes: Uint8Array, accessRights: U8 | null) {
    this.bytes = bytes;
    this.accessRights = accessRights;
  }

  public getBytes(): Uint8Array {
    return this.bytes;
  }

  public getAccessRights(): U8 | null {
    return this.accessRights;
  }

  static fromBytes(bytes: Uint8Array): URef | null {
    var urefBytes = bytes.subarray(0, UREF_ADDR_LENGTH);

    var accessRightsBytes = decodeOptional(bytes.subarray(UREF_ADDR_LENGTH));
    if (accessRightsBytes != null) {
      var accessRights = <U8>(<Uint8Array>accessRightsBytes)[0];
      var uref = new URef(urefBytes, accessRights);
      return uref;
    }
    else {
      return new URef(urefBytes, <U8>null);
    }
  }

  toBytes(): Array<u8> {
    var result = new Array<u8>(this.bytes.length);
    for (var i = 0; i < this.bytes.length; i++) {
      result[i] = this.bytes[i];
    }
    // var result = Object.assign([], this.toBytes); // NOTE: Clone?
    if (this.accessRights == null) {
      result.push(0);
    }
    else {
      result.push(1);
      result.push(<u8>this.accessRights);
    }
    return result;
  }
}


export function decodeOptional(bytes: Uint8Array): Uint8Array | null {
  if (bytes.length < 1) {
    return null;
  }

  if (bytes[0] == 1) {
    return bytes.subarray(1);
  }
  else {
    return null;
  }
}

function getMainPurse(): URef | null {
  var data = new Uint8Array(PURSE_ID_SERIALIZED_LENGTH);
  data.fill(0);
  externals.get_main_purse(data.dataStart);
  return URef.fromBytes(data);
}

export const enum SystemContract {
  Mint = 0,
  ProofOfStake = 1,
}

function getSystemContract(system_contract: SystemContract): URef | null {
  var data = new Uint8Array(UREF_SERIALIZED_LENGTH);
  var ret = externals.get_system_contract(<u32>system_contract, data.dataStart, data.length);
  if (ret > 0) {
    // TODO: revert
    return null;
  }
  return URef.fromBytes(data);
}


enum KeyVariant {
  ACCOUNT_ID = 0,
  HASH_ID = 1,
  UREF_ID = 2,
  LOCAL_ID = 3,
}

export class Key {
  variant: KeyVariant;
  value: URef; // NOTE: For simplicity I treat this as bytes of "union"

  static fromURef(uref: URef): Key {
    var key = new Key();
    key.variant = KeyVariant.UREF_ID;
    key.value = uref;
    return key;
  }
  
  toBytes(): Array<u8> {
    var bytes = new Array<u8>();
    bytes.push(<u8>this.variant)
    bytes = bytes.concat(this.value.toBytes());
    return bytes;
  }
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

export function toBytesString(s: String): u8[] {
  var prefix = toBytesU32(<u32>s.length);
  for (var i = 0; i < s.length; i++) {
    var charCode = s.charCodeAt(i);
    // Assumes ascii encoding (i.e. charCode < 0x80)
    prefix.push(<u8>charCode);
  }
  return prefix;
}

export function toBytesArrayU8(arr: Array<u8>): u8[] {
  var prefix = toBytesU32(<u32>arr.length);
  return prefix.concat(arr);
}

export function serializeArguments(values: Array<u8>[]): Array<u8> {
  var prefix = toBytesU32(<u32>values.length);
  for (var i = 0; i < values.length; i++) {
    prefix = prefix.concat(toBytesArrayU8(values[i]));
  }
  return prefix;
}


function callContract(key: Key, args: Array<u8>[]): Uint8Array | null {
  var keyBytes = key.toBytes();
  var argBytes = serializeArguments(args);
  var extraURefs = serializeArguments([]);

  var resultSize = new Uint32Array(1);
  resultSize.fill(0);

  var ret = externals.call_contract(
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

  var hostBufSize = resultSize[0];

  return readHostBuffer(hostBufSize);
}

function readHostBuffer(count: u32): Uint8Array | null {
  var result = new Uint8Array(count);

  var resultSize = new Uint32Array(1);
  var ret = externals.read_host_buffer(result.dataStart, result.length, resultSize.dataStart);
  if (ret > 0) {
    return null;
  }
  return result;
}

export function call(): void {
  // TODO: Keep `as/` as lib only, move this to separate directory (maybe `as/contracts`)

  var amountBytes = getArg(0);
  if (amountBytes == null) {
    externals.revert(1);
    return;
  }

  var mainPurse = getMainPurse();
  if (mainPurse == null) {
    externals.revert(2);
    return;
  }

  var proofOfStake = getSystemContract(SystemContract.ProofOfStake);
  if (proofOfStake == null) {
    externals.revert(3);
    return;
  }

  var key = Key.fromURef(<URef>proofOfStake);
  var output = callContract(key, [
    toBytesString("get_payment_purse"),
  ]);

  if (output == null) {
    externals.revert(4);
    return;
  }

  var paymentPurse = URef.fromBytes(output);
  if (paymentPurse == null) {
    externals.revert(5);
  }

  externals.revert(6);
}
