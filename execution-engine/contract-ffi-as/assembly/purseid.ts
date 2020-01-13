import {URef} from "./uref";
import * as externals from "./externals";
import {fromBytesU32} from "./bytesrepr";
import {readHostBuffer} from "./index";
import {U512} from "./bignum";
import {Error, ErrorCode} from "./error";
import {UREF_SERIALIZED_LENGTH, PURSE_ID_SERIALIZED_LENGTH} from "./constants";


export class PurseId {
    private uref: URef;

    constructor(uref: URef) {
        this.uref = uref;
    }

    static getMainPurse(): PurseId | null {
        let data = new Uint8Array(PURSE_ID_SERIALIZED_LENGTH);
        data.fill(0);
        externals.get_main_purse(data.dataStart);
        let uref = URef.fromBytes(data);
        if (uref === null)
            return null;
        let purseId = new PurseId(uref);
        return purseId;
    }

    toBytes(): Array<u8>{
        return this.uref.toBytes();
    }

    static fromBytes(bytes: Uint8Array): PurseId | null {
        let uref = URef.fromBytes(bytes);
        if(uref === null)
            return null;
        return new PurseId(uref);
    }

    static createPurse(): PurseId | null {
        let bytes = new Uint8Array(PURSE_ID_SERIALIZED_LENGTH);
        let ret = externals.create_purse(
            bytes.dataStart,
            bytes.length
            );
        if (ret == 0){
            Error.fromErrorCode(ErrorCode.PurseNotCreated).revert();
            return null;
        }

        let uref = URef.fromBytes(bytes);
        if(uref === null){
            Error.fromErrorCode(ErrorCode.PurseNotCreated).revert();
            return null;
        }

        return new PurseId(uref);
    }

    asURef(): URef{
        return this.uref;
    }

    getBalance(): u32 | null {
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

        let balance = fromBytesU32(bytes);
        if (balance === null) {
            return null;
        }

        return <u32>balance;
    }

    transferToAccount(target: Uint8Array, amount: U512): i32 {
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
            // NOTE: amount has U512 type but is not deserialized throughout the execution, as there's no direct replacement for big ints
            amountBytes.dataStart,
            amountBytes.length,
        );
        return ret;
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
}
