import * as CL from "../../../../contract-ffi-as/assembly";
import {Error, ErrorCode} from "../../../../contract-ffi-as/assembly/error";
import {fromBytesU64, fromBytesArrayU8} from "../../../../contract-ffi-as/assembly/bytesrepr";
import {transferToAccount} from "../../../../contract-ffi-as/assembly";
import {U512} from "../../../../contract-ffi-as/assembly/bignum";

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

  let amount = fromBytesU64(amountBytes);
  if (amount == null) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }

  let amount512 = new U512(<U64>amount);

  let transferRet = transferToAccount(accountBytes, amount512);
  if (transferRet > 0) {
    Error.fromErrorCode(ErrorCode.Transfer).revert();
    return;
  }
}
