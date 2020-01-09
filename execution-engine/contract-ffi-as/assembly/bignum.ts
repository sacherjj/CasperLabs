import {fromBytesU32, toBytesU32} from "./bytesrepr";

export class U512 {
    private value: u32;

    constructor(value: u32) {
        this.value = value;
    }

    getValue(): u32 {
        return this.value;
    }

    static fromBytes(bytes: Uint8Array): U512 | null {
        if (bytes.length < 1) {
            return null;
        }

        const lengthPrefix = bytes[0];

        let shift = 0;
        var result = 0;
        for (var i = 0; i < lengthPrefix; i++) {
            result += (bytes[i + 1] * (1 << shift));
            shift += 8;
        }
        return new U512(result);
    }

    toBytes(): Array<u8> {
        var bytes = toBytesU32(this.value);
        
        var zerosAtBack = bytes.length - 1;
        while (bytes[zerosAtBack] == 0) {
            zerosAtBack--;
        }

        var nonZeroBytes = zerosAtBack + 1;
        var result = new Array(nonZeroBytes + 1);

        result[0] = <u8>nonZeroBytes;

        for (var i = 0; i < nonZeroBytes; i++) {
            result[i + 1] = bytes[i];
        }
        return result;
    }
}
