//@ts-nocheck
import * as CL from "../../../../contract-as/assembly";
import {Error} from "../../../../contract-as/assembly/error";
import {U512} from "../../../../contract-as/assembly/bignum";
import {Key} from "../../../../contract-as/assembly/key";
import {URef} from "../../../../contract-as/assembly/uref";
import {putKey} from "../../../../contract-as/assembly";
import {getMainPurse} from "../../../../contract-as/assembly/account";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {createPurse, transferFromPurseToPurse} from "../../../../contract-as/assembly/purse";

enum Args{
    DestinationPurseName = 0,
    Amount = 1
}

enum CustomError{
    MissingAmountArg = 1,
    InvalidAmountArg = 2,
    MissingDestinationArg = 3,
    InvalidDestinationArg = 4,
    UnableToGetMainPurse = 5,
    FailedToCreateDestinationPurse = 6
}

export function call(): void {
    const amountArg = CL.getArg(Args.Amount);
    if (amountArg === null) {
        Error.fromUserError(<u16>CustomError.MissingAmountArg).revert();
        return;
    }
    const amountResult = U512.fromBytes(amountArg);
    if (amountResult.hasError()) {
        Error.fromUserError(<u16>CustomError.InvalidAmountArg).revert();
        return;
    }
    let amount = amountResult.value;
    const destinationPurseNameArg = CL.getArg(Args.DestinationPurseName);
    if (destinationPurseNameArg === null) {
        Error.fromUserError(<u16>CustomError.MissingDestinationArg).revert();
        return;
    }
    const destinationPurseNameResult = fromBytesString(destinationPurseNameArg);
    if (destinationPurseNameResult.hasError()) {
        Error.fromUserError(<u16>CustomError.InvalidDestinationArg).revert();
        return;
    }
    let destinationPurseName = destinationPurseNameResult.value;
    const maybeMainPurse = getMainPurse();
    if (maybeMainPurse === null) {
        Error.fromUserError(<u16>CustomError.UnableToGetMainPurse).revert();
        return;
    }
    const mainPurse = <URef>maybeMainPurse;
    const maybeDestinationPurse = createPurse();
    if (maybeDestinationPurse === null){
        Error.fromUserError(<u16>CustomError.FailedToCreateDestinationPurse).revert();
        return;
    }
    const destinationPurse = <URef>maybeDestinationPurse;
    const result = transferFromPurseToPurse(mainPurse,destinationPurse, <U512>amount);
    const error = Error.fromResult(result);
    if (error !== null) {
        error.revert();
        return;
    }
    putKey(destinationPurseName, <Key>Key.fromURef(destinationPurse));
}
