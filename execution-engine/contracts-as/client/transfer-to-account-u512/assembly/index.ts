import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {U512} from "../../../../contract-as/assembly/bignum";
import {getMainPurse} from "../../../../contract-as/assembly/account";
import {transferFromPurseToAccount, TransferredTo} from "../../../../contract-as/assembly/purse";

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
    const amountResult = U512.fromBytes(amountBytes);
    if (amountResult.hasError()){
        Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
        return;
    }
    let amount = amountResult.value;
    const mainPurse = getMainPurse();
    if (mainPurse === null){
        Error.fromErrorCode(ErrorCode.InvalidPurse).revert();
        return;
    }
    const result = transferFromPurseToAccount(mainPurse, accountBytes, amount);
    if (result == TransferredTo.TransferError){
        Error.fromErrorCode(ErrorCode.Transfer).revert();
        return;
    }
}
