import * as externals from "./externals";
import {readHostBuffer} from "./index";
import {U512} from "./bignum";
import {Error, ErrorCode} from "./error";
import {UREF_SERIALIZED_LENGTH} from "./constants";
import {URef} from "./uref";

export enum TransferredTo {
    TransferError = -1,
    ExistingAccount = 0,
    NewAccount = 1,
}

export function createPurse(): URef | null {
    let bytes = new Uint8Array(UREF_SERIALIZED_LENGTH);
    let ret = externals.create_purse(
        bytes.dataStart,
        bytes.length
        );
    let error = Error.fromResult(<u32>ret);
    if (error !== null){
        error.revert();
        return null;
    }

    let urefResult = URef.fromBytes(bytes);
    if (urefResult.hasError()) {
        Error.fromErrorCode(ErrorCode.PurseNotCreated).revert();
        return null;
    }

    return urefResult.value;
}

export function getPurseBalance(purse: URef): U512 | null {
    let purseBytes = purse.toBytes();
    let balanceSize = new Array<u32>(1);
    balanceSize[0] = 0;

    let retBalance = externals.get_balance(
        purseBytes.dataStart,
        purseBytes.length,
        balanceSize.dataStart,
    );
    if (retBalance > 0) {
        return null;
    }

    let balanceBytes = readHostBuffer(balanceSize[0]);
    if (balanceBytes === null) {
        return null;
    }

    let balanceResult = U512.fromBytes(balanceBytes);
    return balanceResult.ok();
}

export function transferFromPurseToAccount(sourcePurse: URef, targetAccount: Uint8Array, amount: U512): TransferredTo {
    let purseBytes = sourcePurse.toBytes();
    let targetBytes = new Array<u8>(targetAccount.length);
    for (let i = 0; i < targetAccount.length; i++) {
        targetBytes[i] = targetAccount[i];
    }

    let amountBytes = amount.toBytes();

    let ret = externals.transfer_from_purse_to_account(
        purseBytes.dataStart,
        purseBytes.length,
        targetBytes.dataStart,
        targetBytes.length,
        amountBytes.dataStart,
        amountBytes.length,
    );

    if (ret == TransferredTo.ExistingAccount)
        return TransferredTo.ExistingAccount;
    if (ret == TransferredTo.NewAccount)
        return TransferredTo.NewAccount;
    return TransferredTo.TransferError;
}

export function transferFromPurseToPurse(sourcePurse: URef, targetPurse: URef, amount: U512): i32 {
    let sourceBytes = sourcePurse.toBytes();
    let targetBytes = targetPurse.toBytes();
    let amountBytes = amount.toBytes();

    let ret = externals.transfer_from_purse_to_purse(
        sourceBytes.dataStart,
        sourceBytes.length,
        targetBytes.dataStart,
        targetBytes.length,
        amountBytes.dataStart,
        amountBytes.length,
    );
    return ret;
}
