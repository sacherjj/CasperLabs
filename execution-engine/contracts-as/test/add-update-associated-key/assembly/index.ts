// The entry file of your WebAssembly module.
import * as CL from "../../../../contract-ffi-as/assembly";
import {Error, ErrorCode} from "../../../../contract-ffi-as/assembly/error";
import {addAssociatedKey, AddKeyFailure, updateAssociatedKey, UpdateKeyFailure} from "../../../../contract-ffi-as/assembly/account";
import {typedToArray} from "../../../../contract-ffi-as/assembly/utils";


const INIT_WEIGHT: u8 = 1;
const MOD_WEIGHT: u8 = 2;

export function call(): void {
  let accountBytes = CL.getArg(0);
  if (accountBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  if (accountBytes.length != 32) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }

  
  if (addAssociatedKey(typedToArray(accountBytes), INIT_WEIGHT) != AddKeyFailure.Ok) {
    Error.fromUserError(<u16>4464).revert();
    return;
  }

  if (updateAssociatedKey(typedToArray(accountBytes), MOD_WEIGHT) != UpdateKeyFailure.Ok) {
    Error.fromUserError(<u16>4464 + 1).revert();
    return;
  }
}
