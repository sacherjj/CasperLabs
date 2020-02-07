import {SetDecodedBytesCount,
        GetLastError, SetLastError, Error} from "./bytesrepr";
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

    static fromBytes(bytes: Uint8Array): URef | null {
        if (bytes.length < 33) {
            SetLastError(Error.EarlyEndOfStream);
            return null;
        }

        let urefBytes = bytes.subarray(0, UREF_ADDR_LENGTH);
        SetDecodedBytesCount(33);
        if (bytes[UREF_ADDR_LENGTH] == 1) {
            if (bytes.length < 34) {
                SetLastError(Error.EarlyEndOfStream);
                return null;
            }
            let accessRights = bytes[UREF_ADDR_LENGTH + 1];
            SetDecodedBytesCount(33 + 1);
            SetLastError(Error.Ok);
            return new URef(urefBytes, accessRights);
        }
        else {
            let urefBytes = bytes.subarray(0, UREF_ADDR_LENGTH);
            SetLastError(Error.Ok);
            return new URef(urefBytes, AccessRights.NONE);
        }
    }

    toBytes(): Array<u8> {
        let result = new Array<u8>(this.bytes.length);
        for (let i = 0; i < this.bytes.length; i++) {
            result[i] = this.bytes[i];
        }

        if (this.accessRights == AccessRights.NONE) {
            result.push(0);
            return result;
        }

        result.push(1);
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
