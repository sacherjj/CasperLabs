import * as externals from "./externals";
import {readHostBuffer} from ".";
import {KEY_UREF_SERIALIZED_LENGTH} from "./constants";
import {URef} from "./uref";
import {CLValue} from "./clvalue";
import {Error} from "./error";
import {checkTypedArrayEqual, typedToArray} from "./utils";
import {GetDecodedBytesCount, SetDecodedBytesCount,
        SetLastError, GetLastError, Error as BytesreprError} from "./bytesrepr";

export enum KeyVariant {
    ACCOUNT_ID = 0,
    HASH_ID = 1,
    UREF_ID = 2,
}

export class Key {
    variant: KeyVariant;
    hash: Uint8Array | null;
    uref: URef | null;
    account: Uint8Array | null;

    static fromURef(uref: URef): Key {
        let key = new Key();
        key.variant = KeyVariant.UREF_ID;
        key.uref = uref;
        return key;
    }

    static fromHash(hash: Uint8Array): Key {
        let key = new Key();
        key.variant = KeyVariant.HASH_ID;
        key.hash = hash;
        return key;
    }

    static fromAccount(account: Uint8Array): Key {
        let key = new Key();
        key.variant = KeyVariant.ACCOUNT_ID;
        key.account = account;
        return key;
    }

    /// attempts to write `value` under a new Key::URef
    /// this is equivalent to the TURef concept in the rust implementation
    /// if a key is returned it is always of KeyVariant.UREF_ID
    static create(value: CLValue): Key | null {
        const valueBytes = value.toBytes();
        let keyBytes = new Uint8Array(KEY_UREF_SERIALIZED_LENGTH);
        externals.new_uref(
            keyBytes.dataStart,
            valueBytes.dataStart,
            valueBytes.length
        );
        const key = Key.fromBytes(keyBytes);
        if (key === null) {
            return null;
        }
        if (key.variant != KeyVariant.UREF_ID) {
            return null;
        }
        return <Key>key;
    }

    static fromBytes(bytes: Uint8Array): Key | null {
        if (bytes.length < 1) {
            SetLastError(BytesreprError.EarlyEndOfStream);
            return null;
        }
        const tag = bytes[0];
        SetDecodedBytesCount(1);
        if (tag == KeyVariant.HASH_ID) {
            var hashBytes = bytes.subarray(1, 32 + 1);
            SetDecodedBytesCount(1 + 32);
            SetLastError(BytesreprError.Ok);
            return Key.fromHash(hashBytes);
        }
        else if (tag == KeyVariant.UREF_ID) {
            var urefBytes = bytes.subarray(1);

            let savedOffset = GetDecodedBytesCount();

            var uref = URef.fromBytes(urefBytes);
            if (uref === null) {
                SetLastError(BytesreprError.FormattingError);
                return null;
            }

            let decodedBytes = GetDecodedBytesCount();
            SetDecodedBytesCount(savedOffset + decodedBytes);
            SetLastError(BytesreprError.Ok);
            return Key.fromURef(<URef>uref);
        }
        else if (tag == KeyVariant.ACCOUNT_ID) {
            var accountBytes = bytes.subarray(1, 32 + 1);
            SetDecodedBytesCount(1 + 32);
            SetLastError(BytesreprError.Ok);
            return Key.fromAccount(accountBytes);
        }
        else {
            SetLastError(BytesreprError.FormattingError);
            return null;
        }
    }

    toBytes(): Array<u8> {
        if(this.variant == KeyVariant.UREF_ID){
            let bytes = new Array<u8>();
            bytes.push(<u8>this.variant)
            bytes = bytes.concat((<URef>this.uref).toBytes());
            return bytes;
        }
        else if (this.variant == KeyVariant.HASH_ID) {
            var hashBytes = <Uint8Array>this.hash;
            let bytes = new Array<u8>(1 + hashBytes.length);
            bytes[0] = <u8>this.variant;
            for (let i = 0; i < hashBytes.length; i++) {
                bytes[i + 1] = hashBytes[i];
            }
            return bytes;
        }
        else if (this.variant == KeyVariant.ACCOUNT_ID) {
            var accountBytes = <Uint8Array>this.account;
            let bytes = new Array<u8>(1);
            bytes[0] = <u8>this.variant;
            bytes = bytes.concat(typedToArray(accountBytes));
            return bytes;
        }
        else {
            return <Array<u8>>unreachable();
        }
    }

    isURef(): bool {
        return this.variant == KeyVariant.UREF_ID;
    }

    toURef(): URef {
        return <URef>this.uref;
    }

    read(): Uint8Array | null {
        const keyBytes = this.toBytes();
        let valueSize = new Uint8Array(1);
        const ret = externals.read_value(keyBytes.dataStart, keyBytes.length, valueSize.dataStart);
        const error = Error.fromResult(ret);
        if (error != null) {
            // TODO: How do we differentiate lack of value from other errors?
            error.revert();
            return null;
        }
        // TODO: How can we have `read<T>` that would deserialize host bytes into T?
        return readHostBuffer(valueSize[0]);
    }

    write(value: CLValue): void {
        const keyBytes = this.toBytes();
        const valueBytes = value.toBytes();
        externals.write(
            keyBytes.dataStart,
            keyBytes.length,
            valueBytes.dataStart,
            valueBytes.length
        );
    }

    add(value: CLValue): void {
        const keyBytes = this.toBytes();
        const valueBytes = value.toBytes();

        externals.add(
            keyBytes.dataStart,
            keyBytes.length,
            valueBytes.dataStart,
            valueBytes.length
        );
    }

    @operator("==")
    equalsTo(other: Key): bool {
        if (this.variant == KeyVariant.UREF_ID) {
            if (other.variant == KeyVariant.UREF_ID) {
                return <URef>this.uref == <URef>other.uref;
            }
            else {
                return false;
            }
        }
        else if (this.variant == KeyVariant.HASH_ID) {
            if (other.variant == KeyVariant.HASH_ID) {
                return checkTypedArrayEqual(<Uint8Array>this.hash, <Uint8Array>other.hash);

            }
            else {
                return false;
            }
        }
        else if (this.variant == KeyVariant.ACCOUNT_ID) {
            if (other.variant == KeyVariant.ACCOUNT_ID) {
                return checkTypedArrayEqual(<Uint8Array>this.account, <Uint8Array>other.account);
            }
            else {
                return false;
            }
        }
        else {
            return false;
        }
    }

    @operator("!=")
    notEqualsTo(other: Key): bool {
        return !this.equalsTo(other);
    }
}
