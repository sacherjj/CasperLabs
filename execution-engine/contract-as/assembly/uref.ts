import {Error, Result, Ref} from "./bytesrepr";
import {UREF_ADDR_LENGTH} from "./constants";
import {checkTypedArrayEqual} from "./utils";
import {is_valid_uref, revert} from "./externals";

export enum AccessRights{
    NONE = 0x0,
    READ = 0x1,
    WRITE = 0x2,
    READ_WRITE = 0x3,
    ADD = 0x4,
    READ_ADD = 0x5,
    ADD_WRITE = 0x6,
    READ_ADD_WRITE = 0x07,
}

export class URef {
    private bytes: Uint8Array;
    private accessRights: AccessRights

    constructor(bytes: Uint8Array, accessRights: AccessRights) {
        this.bytes = bytes;
        this.accessRights = accessRights;
    }

    public getBytes(): Uint8Array {
        return this.bytes;
    }

    public getAccessRights(): AccessRights {
        return this.accessRights;
    }

    public isValid(): boolean{
        const urefBytes = this.toBytes();
        let ret = is_valid_uref(
            urefBytes.dataStart,
            urefBytes.length
        );
        return ret !== 0;
    }

    static fromBytes(bytes: Uint8Array): Result<URef> {
        if (bytes.length < 33) {
            return new Result<URef>(null, Error.EarlyEndOfStream, 0);
        }

        let urefBytes = bytes.subarray(0, UREF_ADDR_LENGTH);
        let currentPos = 33;

		let accessRights = bytes[UREF_ADDR_LENGTH];
		let uref = new URef(urefBytes, accessRights);
		let ref = new Ref<URef>(uref);
		return new Result<URef>(ref, Error.Ok, currentPos);
    }

    toBytes(): Array<u8> {
        let result = new Array<u8>(this.bytes.length);
        for (let i = 0; i < this.bytes.length; i++) {
            result[i] = this.bytes[i];
        }

        result.push(<u8>this.accessRights);
        return result;
    }

    @operator("==")
    equalsTo(other: URef): bool {
        return checkTypedArrayEqual(this.bytes, other.bytes) && this.accessRights == other.accessRights;
    }


    @operator("!=")
    notEqualsTo(other: URef): bool {
        return !this.equalsTo(other);
    }
}
