import {Option} from "./option";
import {Key} from "./key";

const UREF_ADDR_LENGTH = 32;
const OPTION_TAG_SERIALIZED_LENGTH = 1;
const ACCESS_RIGHTS_SERIALIZED_LENGTH = 1;
export const UREF_SERIALIZED_LENGTH = UREF_ADDR_LENGTH + OPTION_TAG_SERIALIZED_LENGTH + ACCESS_RIGHTS_SERIALIZED_LENGTH;

export class URef {
    private bytes: Uint8Array;
    private accessRights: U8 | null = null; // NOTE: Optional access rights are currently marked as "null"

    constructor(bytes: Uint8Array, accessRights: U8 | null) {
        this.bytes = bytes;
        this.accessRights = accessRights;
    }

    public getBytes(): Uint8Array {
        return this.bytes;
    }

    public getAccessRights(): U8 | null {
        return this.accessRights;
    }

    public asKey(): Key{
        return Key.fromURef(this);
    }

    static fromBytes(bytes: Uint8Array): URef | null {
        let urefBytes = bytes.subarray(0, UREF_ADDR_LENGTH);
        let accessRightsBytes =  Option.fromBytes(bytes.subarray(UREF_ADDR_LENGTH));
        if(accessRightsBytes.isNone())
            return new URef(urefBytes, <U8>null);

        let accessRights = <U8>(<Uint8Array>accessRightsBytes.unwrap())[0];
        return new URef(urefBytes, accessRights);
    }

    toBytes(): Array<u8> {
        let result = new Array<u8>(this.bytes.length);
        for (let i = 0; i < this.bytes.length; i++) {
            result[i] = this.bytes[i];
        }
        // var result = Object.assign([], this.toBytes); // NOTE: Clone?
        if (this.accessRights == null) {
            result.push(0);
        }
        else {
            result.push(1);
            result.push(<u8>this.accessRights);
        }
        return result;
    }

    //read():CLValue{}
}
