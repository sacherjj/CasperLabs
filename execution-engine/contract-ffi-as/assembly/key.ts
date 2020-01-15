import {URef} from "./uref";
import {CLValue} from "./clvalue";
import {Error} from "./error";
import {UREF_SERIALIZED_LENGTH, KEY_ID_SERIALIZED_LENGTH, KEY_UREF_SERIALIZED_LENGTH} from "./constants";
import * as externals from "./externals";
import { readHostBuffer } from ".";

export enum KeyVariant {
    ACCOUNT_ID = 0,
    HASH_ID = 1,
    UREF_ID = 2,
    LOCAL_ID = 3,
}

export class Key {
    variant: KeyVariant;
    hash: Uint8Array | null;
    uref: URef | null;

    static fromURef(uref: URef): Key {
        let key = new Key();
        key.variant = KeyVariant.UREF_ID;
        key.uref = uref;
        return key;
    }

    static fromHash(hash: Uint8Array): Key{
        let key = new Key();
        key.variant = KeyVariant.HASH_ID;
        key.hash = hash;
        return key;
    }

    static newInitialized(value: CLValue): Key | null {
        const valueBytes = value.toBytes();
        let keyBytes = new Uint8Array(KEY_UREF_SERIALIZED_LENGTH);
        externals.new_uref(
            keyBytes.dataStart,
            valueBytes.dataStart,
            valueBytes.length);
        const key = Key.fromBytes(keyBytes);
        if (key === null) {
            return null;
        }
        if (key.variant != KeyVariant.UREF_ID) {
            return null;
        }
        return <Key>key;
    }

    add(value: CLValue): void {
        const keyBytes = this.toBytes();
        const valueBytes = value.toBytes();

        externals.add(
            keyBytes.dataStart,
            keyBytes.length,
            valueBytes.dataStart,
            valueBytes.length);
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

    static fromBytes(bytes: Uint8Array): Key | null {
        if (bytes.length == 0) {
            return null;
        }
        const tag = bytes[0];
        if (tag == KeyVariant.HASH_ID) {
            var hashBytes = bytes.subarray(1);
            return Key.fromHash(hashBytes);
        }
        else if (tag == KeyVariant.UREF_ID) {
            var urefBytes = bytes.subarray(1);
            var uref = URef.fromBytes(urefBytes);
            if (uref === null) {
                return null;
            }
            return Key.fromURef(<URef>uref);
        }
        else {
            throw 123; // unreachable?
        }
    }

    toBytes(): Array<u8> {
        if(this.variant === KeyVariant.UREF_ID){
            let bytes = new Array<u8>();
            bytes.push(<u8>this.variant)
            bytes = bytes.concat((<URef>this.uref).toBytes());
            return bytes;
        }
        else if (this.variant === KeyVariant.HASH_ID) {
            var hashBytes = <Uint8Array>this.hash;
            let bytes = new Array<u8>(1 + hashBytes.length);
            bytes[0] = <u8>this.variant;
            for (let i = 0; i < hashBytes.length; i++) {
                bytes[i + 1] = hashBytes[i];
            }
            return bytes;
        }
        else {
            throw 123;
        }
    }

    @operator("==")
    equalsTo(other: Key): bool {
        if (this.variant === KeyVariant.UREF_ID) {
            if (other.variant == KeyVariant.UREF_ID) {
                return <URef>this.uref == <URef>other.uref;
            }
            else {
                return false;
            }
        }
        else if (this.variant == KeyVariant.HASH_ID) {
            if (other.variant == KeyVariant.HASH_ID) {
                return <Uint8Array>this.hash == <Uint8Array>other.hash;
            }
            else {
                return false;
            }
        }
        else {
            unreachable();
            return false;
        }
    }

    @operator("!=")
    notEqualsTo(other: Key): bool {
        return !this.equalsTo(other);
    }
}
