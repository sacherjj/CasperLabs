//@ts-nocheck
import * as CL from "../../../../contract-as/assembly";
import {Error} from "../../../../contract-as/assembly/error";
import {U512} from "../../../../contract-as/assembly/bignum";
import {getMainPurse} from "../../../../contract-as/assembly/account";
import {Key} from "../../../../contract-as/assembly/key";
import {PurseId} from "../../../../contract-as/assembly/purseid";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {putKey} from "../../../../contract-as/assembly";

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
    const amount = U512.fromBytes(amountArg);
    if (amount === null) {
        Error.fromUserError(<u16>CustomError.InvalidAmountArg).revert();
        return;
    }
    const destinationPurseNameArg = CL.getArg(Args.DestinationPurseName);
    if (destinationPurseNameArg === null) {
        Error.fromUserError(<u16>CustomError.MissingDestinationArg).revert();
        return;
    }
    const destinationPurseName = fromBytesString(destinationPurseNameArg);
    if (destinationPurseName === null){
        Error.fromUserError(<u16>CustomError.InvalidDestinationArg).revert();
        return;
    }
    const maybeMainPurse = getMainPurse();
    if (maybeMainPurse === null) {
        Error.fromUserError(<u16>CustomError.UnableToGetMainPurse).revert();
        return;
    }
    const mainPurse = <PurseId>maybeMainPurse;
    const maybeDestinationPurse = PurseId.create();
    if (maybeDestinationPurse === null){
        Error.fromUserError(<u16>CustomError.FailedToCreateDestinationPurse).revert();
        return;
    }
    const destinationPurse = <PurseId>maybeDestinationPurse;
    const result = mainPurse.transferToPurse(destinationPurse, <U512>amount);
    const error = Error.fromResult(result);
    if (error !== null) {
        error.revert();
        return;
    }
    putKey(destinationPurseName, <Key>Key.fromURef(destinationPurse.asURef()));
}
