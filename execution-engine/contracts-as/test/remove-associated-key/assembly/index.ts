// The entry file of your WebAssembly module.
import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {removeAssociatedKey, RemoveKeyFailure} from "../../../../contract-as/assembly/account";
import {typedToArray} from "../../../../contract-as/assembly/utils";

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
  
  if (removeAssociatedKey(typedToArray(accountBytes)) != RemoveKeyFailure.Ok) {
    Error.fromUserError(<u16>4464).revert();
    return;
  }
}
