import {URef} from "./uref";

export enum KeyVariant {
    ACCOUNT_ID = 0,
    HASH_ID = 1,
    UREF_ID = 2,
    LOCAL_ID = 3,
}

export class Key {
    variant: KeyVariant;
    value: Uint8Array;
    uref: URef;

    static fromURef(uref: URef): Key {
        let key = new Key();
        key.variant = KeyVariant.UREF_ID;
        key.value = uref.getBytes();
        key.uref = uref;
        return key;
    }

    static fromHash(hash: Uint8Array): Key{
        let key = new Key();
        key.variant = KeyVariant.HASH_ID;
        key.value = hash;
        return key;
    }

    toBytes(): Array<u8> {
        if(this.variant === KeyVariant.UREF_ID){
            let bytes = new Array<u8>();
            bytes.push(<u8>this.variant)
            bytes = bytes.concat(this.uref.toBytes());
            return bytes;
        }

        const len = this.value.length;
        let result = new Array<u8>(len);
        for (let i = 0; i < len; i++) {
            result[i] = this.value[i];
        }
        return result;
    }
}
