import * as CL from "../../../../contract-ffi-as/assembly";
import {Error, ErrorCode} from "../../../../contract-ffi-as/assembly/error";
import {transferToAccount} from "../../../../contract-ffi-as/assembly";

export function call(): void {
  let accountBytes = CL.getArg(0);
  if (accountBytes == null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  let amountBytes = CL.getArg(1);
  if (amountBytes == null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  let transferRet = transferToAccount(accountBytes, amountBytes);
  if (transferRet > 0) {
    Error.fromErrorCode(ErrorCode.Transfer).revert();
    return;
  }
}
