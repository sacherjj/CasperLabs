import {URef} from "./uref";

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
}
