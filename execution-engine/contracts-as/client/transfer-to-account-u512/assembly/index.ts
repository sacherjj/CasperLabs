import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {U512} from "../../../../contract-as/assembly/bignum";
import {getMainPurse} from "../../../../contract-as/assembly/account";
import {TransferredTo} from "../../../../contract-as/assembly/purseid";

enum Args{
  Account = 0,
  Amount = 1
}

export function call(): void {
  const accountBytes = CL.getArg(Args.Account);
  if (accountBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }
  const amountBytes = CL.getArg(Args.Amount);
  if (amountBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }
  const amount = U512.fromBytes(amountBytes);
  if(amount === null){
      Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
      return;
    }
  const mainPurse = getMainPurse();
  if (mainPurse === null){
      Error.fromErrorCode(ErrorCode.InvalidPurse).revert();
      return;
  }
    const result = mainPurse.transferToAccount(accountBytes, <U512>amount);
  if (result == TransferredTo.TransferError){
      Error.fromErrorCode(ErrorCode.Transfer).revert();
      return;
  }
}
