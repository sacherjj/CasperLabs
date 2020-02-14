//@ts-nocheck
import * as CL from "../../../../contract-as/assembly";
import {Error} from "../../../../contract-as/assembly/error";
import {U512} from "../../../../contract-as/assembly/bignum";
import {getMainPurse} from "../../../../contract-as/assembly/account";
import {Key} from "../../../../contract-as/assembly/key";
import {getKey, hasKey, putKey} from "../../../../contract-as/assembly";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {URef} from "../../../../contract-as/assembly/uref";
import {createPurse, getPurseBalance, transferFromPurseToPurse} from "../../../../contract-as/assembly/purse";

const PURSE_MAIN = "purse:main";
const PURSE_TRANSFER_RESULT = "purse_transfer_result";
const MAIN_PURSE_BALANCE = "main_purse_balance";
const SUCCESS_MESSAGE = "Ok(())";
const TRANSFER_ERROR_MESSAGE = "Err(ApiError::Transfer [14])";

enum Args {
    SourcePurse = 0,
    DestinationPurse = 1,
    Amount = 2,
}

enum CustomError {
    UnableToGetMainPurse = 1,
    UnableToGetMainPurseKey = 2,
    MissingSourcePurseArg = 3,
    InvalidSourcePurseArg = 4,
    MissingDestinationPurseArg = 5,
    InvalidDestinationPurseArg = 6,
    UnableToCreateDestinationPurse = 7,
    UnableToCreateDestinationPurseKey = 8,
    MissingDestinationPurse = 9,
    UnableToStoreResult = 10,
    UnableToStoreBalance = 11,
    MissingAmountArg = 12,
    InvalidAmountArg = 13,
    InvalidSourcePurseKey = 103,
    UnexpectedSourcePurseKeyVariant = 104,
    InvalidDestinationPurseKey = 105,
    UnexpectedDestinationPurseKeyVariant = 106,
    UnableToGetBalance = 107,
}

export function call(): void {
    const maybeMainPurse = getMainPurse();
    if (maybeMainPurse === null) {
        Error.fromUserError(<u16>CustomError.UnableToGetMainPurse).revert();
        return;
    }
    const mainPurse = <URef>maybeMainPurse;
    const mainPurseKey = Key.fromURef(mainPurse);
    if(mainPurseKey === null){
        Error.fromUserError(<u16>CustomError.UnableToGetMainPurseKey).revert();
        return;
    }
    putKey(PURSE_MAIN, <Key>mainPurseKey);
    const sourcePurseKeyNameArg = CL.getArg(Args.SourcePurse);
    if (sourcePurseKeyNameArg === null) {
        Error.fromUserError(<u16>CustomError.MissingSourcePurseArg).revert();
        return;
    }
    const maybeSourcePurseKeyName = fromBytesString(sourcePurseKeyNameArg);
    if(maybeSourcePurseKeyName.hasError()) {
        Error.fromUserError(<u16>CustomError.InvalidSourcePurseArg).revert();
        return;
    }
    const sourcePurseKeyName = maybeSourcePurseKeyName.value;
    const sourcePurseKey = getKey(sourcePurseKeyName);
    if (sourcePurseKey === null){
        Error.fromUserError(<u16>CustomError.InvalidSourcePurseKey).revert();
        return;
    }
    if(!sourcePurseKey.isURef()){
        Error.fromUserError(<u16>CustomError.UnexpectedSourcePurseKeyVariant).revert();
        return;
    }
    const sourcePurse = sourcePurseKey.toURef();

    const destinationPurseKeyNameArg = CL.getArg(Args.DestinationPurse);
    if (destinationPurseKeyNameArg === null) {
        Error.fromUserError(<u16>CustomError.MissingDestinationPurseArg).revert();
        return;
    }
    const maybeDestinationPurseKeyName = fromBytesString(destinationPurseKeyNameArg);
    if(maybeDestinationPurseKeyName.hasError()){
        Error.fromUserError(<u16>CustomError.InvalidDestinationPurseArg).revert();
        return;
    }
    let destinationPurseKeyName = maybeDestinationPurseKeyName.value;
    let destinationPurse: URef | null;
    let destinationKey: Key | null;
    if(!hasKey(destinationPurseKeyName)){
        destinationPurse = createPurse();
        if (destinationPurse === null){
            Error.fromUserError(<u16>CustomError.UnableToCreateDestinationPurse).revert();
            return;
        }
        destinationKey = Key.fromURef(destinationPurse);
        if(destinationKey === null){
            Error.fromUserError(<u16>CustomError.UnableToCreateDestinationPurseKey).revert();
            return;
        }
        putKey(destinationPurseKeyName, <Key>destinationKey);
    } else {
        destinationKey = getKey(destinationPurseKeyName);
        if(destinationKey === null){
            Error.fromUserError(<u16>CustomError.InvalidDestinationPurseKey).revert();
            return;
        }
        if(!destinationKey.isURef()){
            Error.fromUserError(<u16>CustomError.UnexpectedDestinationPurseKeyVariant).revert();
            return;
        }
        destinationPurse = destinationKey.toURef();
    }
    if(destinationPurse === null){
        Error.fromUserError(<u16>CustomError.MissingDestinationPurse).revert();
        return;
    }

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
    const amount = amountResult.value;

    const result = transferFromPurseToPurse(<URef>sourcePurse, <URef>destinationPurse, amount);
    let message = SUCCESS_MESSAGE;
    if (result !== null && result > 0){
        message = TRANSFER_ERROR_MESSAGE;
    }
    const resultKey = Key.create(CLValue.fromString(message));
    const finalBalance = getPurseBalance(<URef>sourcePurse);
    if(finalBalance === null){
        Error.fromUserError(<u16>CustomError.UnableToGetBalance).revert();
        return;
    }
    const balanceKey = Key.create(CLValue.fromU512(finalBalance));
    if(balanceKey === null){
        Error.fromUserError(<u16>CustomError.UnableToStoreBalance).revert();
        return;
    }
    if(resultKey === null){
        Error.fromUserError(<u16>CustomError.UnableToStoreResult).revert();
        return;
    }
    putKey(PURSE_TRANSFER_RESULT, <Key>resultKey);
    putKey(MAIN_PURSE_BALANCE, <Key>balanceKey);
}
