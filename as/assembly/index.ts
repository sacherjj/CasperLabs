// The entry file of your WebAssembly module.
import * as externals from "./externals";

function get_arg_size(i: u32): U32 | null {
  // TODO: Docs aren't clear on pointers, but perhaps `var size = <u32>0; changetype<usize>(size);` might take a pointer of a value we could pass
  var size = new Array<u32>(1);
  size[0] = 0;

  var ret = externals.get_arg_size(i, size.dataStart);
  if (ret > 0) {
    return null;
  }
  return <U32>size[0];
}

function get_arg(i: u32): Uint8Array | null {
  var arg_size = get_arg_size(i);
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

  public getBytes(): Uint8Array {
    return this.bytes;
  }

  public getAccessRights(): U8 | null {
    return this.accessRights;
  }

  static fromBytes(bytes: Uint8Array): URef | null {
    var uref = new URef();
    uref.bytes = bytes.subarray(0, UREF_ADDR_LENGTH);

    var accessRightsBytes = decodeOptional(bytes.subarray(UREF_ADDR_LENGTH));
    if (accessRightsBytes != null) {
      uref.accessRights = <U8>(<Uint8Array>accessRightsBytes)[0];
    }
    return uref;
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




function get_main_purse(): URef | null {
  var data = new Uint8Array(PURSE_ID_SERIALIZED_LENGTH);
  data.fill(0);
  externals.get_main_purse(data.dataStart);
  return URef.fromBytes(data);
}

export function call(): void {
  var amount_bytes = get_arg(0);
  if (amount_bytes == null) {
    externals.revert(1);
    return;
  }

  var mainPurse = get_main_purse();
  if (mainPurse == null) {
    externals.revert(2);
    return;
  }

  externals.revert(3);
}
