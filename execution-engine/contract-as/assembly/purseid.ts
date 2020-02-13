import {URef} from "./uref";
import * as externals from "./externals";
import {readHostBuffer} from "./index";
import {U512} from "./bignum";
import {Error, ErrorCode} from "./error";
import {PURSE_ID_SERIALIZED_LENGTH} from "./constants";
import {Result, Ref} from "./bytesrepr";

export enum TransferredTo {
    TransferError = -1,
    ExistingAccount = 0,
    NewAccount = 1,
}

export class PurseId {
    private uref: URef;

    constructor(uref: URef) {
        this.uref = uref;
    }

    toBytes(): Array<u8>{
        return this.uref.toBytes();
    }

    static fromBytes(bytes: Uint8Array): Result<PurseId> {
        let result = URef.fromBytes(bytes);
        if (result.hasError()) {
            return new Result<PurseId>(null, result.error, result.position);
        }
        let purseId = new PurseId(result.value);
        let ref = new Ref<PurseId>(purseId);
        return new Result<PurseId>(ref, result.error, result.position);
    }

    static create(): PurseId | null {
        let bytes = new Uint8Array(PURSE_ID_SERIALIZED_LENGTH);
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

        return new PurseId(urefResult.value);
    }

    asURef(): URef{
        return this.uref;
    }

    getBalance(): U512 | null {
        let sourceBytes = this.toBytes();
        let balanceSize = new Array<u32>(1);
        balanceSize[0] = 0;

        let retBalance = externals.get_balance(
            sourceBytes.dataStart,
            sourceBytes.length,
            balanceSize.dataStart,
        );
        if (retBalance > 0) {
            return null;
        }

        let bytes = readHostBuffer(balanceSize[0]);
        if (bytes === null) {
            return null;
        }

        let balanceResult = U512.fromBytes(bytes);
        return balanceResult.ok();
    }

    transferToAccount(target: Uint8Array, amount: U512): TransferredTo {
        let sourceBytes = this.toBytes();
        let targetBytes = new Array<u8>(target.length);
        for (let i = 0; i < target.length; i++) {
            targetBytes[i] = target[i];
        }

        let amountBytes = amount.toBytes();

        let ret = externals.transfer_from_purse_to_account(
            sourceBytes.dataStart,
            sourceBytes.length,
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

    transferToPurse(target: PurseId, amount: U512): i32 {
        let sourceBytes = this.toBytes();
        let targetBytes = target.toBytes();
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

    @operator("==")
    equalsTo(other: PurseId): bool {
        return this.uref == other.uref;
    }

    @operator("!=")
    notEqualsTo(other: PurseId): bool {
        return !this.uref.equalsTo(other.uref);
    }
}
