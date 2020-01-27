import {toBytesU64} from "./bytesrepr";

export class BigNum {
    private bytes: Uint32Array;

    constructor(width: usize, initialValue: u64) {
        this.bytes = new Uint32Array(width);
        this.bytes.fill(0);

        if (initialValue > 0) {
            let value = <u64>initialValue;
            assert(width >= 2);
            this.bytes[0] = <u32>(value & <u64>0xffffffff);
            this.bytes[1] = <u32>(value >>> 32);
        }
    }

    @operator("+")
    add(other: BigNum): BigNum {
        assert(this.bytes.length == other.bytes.length);
        let carry = <u64>0;
        for (let i = 0; i < this.bytes.length; i++) {
            let n = carry + <u64>this.bytes[i] + <u64>other.bytes[i];
            this.bytes[i] = <u32>(n & <u64>0xffffffff);
            carry = <u64>(n >>> 32);
        }
        return this;
    }
};

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
