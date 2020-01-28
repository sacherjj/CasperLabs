import {toBytesU64} from "./bytesrepr";

export class U512 {
    private value: U64;

    constructor(value: U64) {
        this.value = value;
    }

    getValue(): U64 {
        return this.value;
    }

    static fromBytes(bytes: Uint8Array): U512 | null {
        if (bytes.length < 1) {
            return null;
        }

        const lengthPrefix = <i32>bytes[0];

        let shift = <u32>0;
        var result = <u64>0;
        for (var i = <i32>0; i < lengthPrefix; i++) {
            result += (bytes[i + 1] * (<u32>1 << shift));
            shift += 8;
        }
        return new U512(<U64>result);
    }

    toBytes(): Array<u8> {
        var bytes = toBytesU64(<u64>this.value);
        
        var zerosAtBack = bytes.length - 1;
        while (bytes[zerosAtBack] == 0) {
            zerosAtBack--;
        }

        var nonZeroBytes = zerosAtBack + 1;
        var result = new Array<u8>(nonZeroBytes + 1);

        result[0] = <u8>nonZeroBytes;

        for (var i = 0; i < nonZeroBytes; i++) {
            result[i + 1] = bytes[i];
        }
        return result;
    }
}
