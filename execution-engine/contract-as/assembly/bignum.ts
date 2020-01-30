import {toBytesU64} from "./bytesrepr";

const HEX_LOWERCASE: string[] = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'];
// ascii -> number value
const HEX_DIGITS: i32[] =
[ -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
  -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
  -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
   0, 1, 2, 3, 4, 5, 6, 7, 8, 9,-1,-1,-1,-1,-1,-1,
  -1,0xa,0xb,0xc,0xd,0xe,0xf,-1,-1,-1,-1,-1,-1,-1,-1,-1,
  -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
  -1,0xa,0xb,0xc,0xd,0xe,0xf,-1,-1,-1,-1,-1,-1,-1,-1,-1,
  -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
  -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
  -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
  -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
  -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
  -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
  -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
  -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
  -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1 ];

export class BigNum {
    private bytes: Uint32Array;

    constructor(width: usize) {
        this.bytes = new Uint32Array(width);
        this.bytes.fill(0);
    }

    setU64(value: u64): void {
        this.bytes.fill(0);
        assert(this.bytes.length >= 2);
        this.bytes[0] = <u32>(value & <u64>0xffffffff);
        this.bytes[1] = <u32>(value >> 32);
    }

    setHex(value: String): void {
        this.bytes.fill(0);

        if (value.length >= 2 && value[0] == '0' && (value[1] == 'x' || value[1] == 'X'))
            value = value.substr(2);

        // Find the length
        let digits = 0;
        while (digits < value.length && HEX_DIGITS[<usize>value.charCodeAt(digits)] != -1 ) {
            digits++;
        }

        // Decodes hex string into an array of bytes
        let bytes = new Uint8Array(this.bytes.length * 4);
        bytes.fill(0);

        // Convert ascii codes into values
        let i = 0;
        while (digits > 0 && i < bytes.length) {
            bytes[i] = HEX_DIGITS[value.charCodeAt(--digits)];

            if (digits > 0) {
                bytes[i] |= <u8>HEX_DIGITS[value.charCodeAt(--digits)] << 4;
                i++;
            }
        }

        // Reinterpret individual bytes back to u32 array
        for (let i = 0; i < this.bytes.length; i++) {
            let num = load<u32>(bytes.dataStart + (i * 4));
            this.bytes[i] = num;
        }
    }

    isZero(): bool {
        for (let i = 0; i < this.bytes.length; i++) {
            if (this.bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    @operator("+")
    add(other: BigNum): BigNum {
        assert(this.bytes.length == other.bytes.length);
        let carry = <u64>0;
        for (let i = 0; i < this.bytes.length; i++) {
            let n = carry + <u64>this.bytes[i] + <u64>other.bytes[i];
            this.bytes[i] = <u32>(n & <u64>0xffffffff);
            carry = <u64>(n >> 32);
        }
        return this;
    }

    @operator.prefix("-")
    neg(): BigNum {
        let ret = new BigNum(this.bytes.length);
        for (let i = 0; i < this.bytes.length; i++) {
            ret.bytes[i] = ~this.bytes[i];
        }
        // todo bin op ++
        let one = new BigNum(this.bytes.length);
        one.setU64(1);
        ret += one;
        return ret;
    }

    @operator("-")
    sub(other: BigNum): BigNum {
        return this.add(-other);
    }

    @operator("*")
    mul(other: BigNum): BigNum {
        assert(this.bytes.length == other.bytes.length);
        let ret = new BigNum(this.bytes.length);

        for (let j = 0; j < this.bytes.length; j++) {
            let carry: u64 = <u64>0;
            for (let i = 0; i + j < this.bytes.length; i++) {
                let n: u64 = carry + <u64>ret.bytes[i + j] + <u64>this.bytes[j] * <u64>other.bytes[i];
                ret.bytes[i + j] = <u32>(n & <u64>0xffffffff);
                carry = <u64>(n >> 32);
            }
        }

        return ret;
    }

    private toHex(): String {
        let bytes = new Uint8Array(this.bytes.length * 4);
        // Copy array of u32 into array of u8
        for (let i = 0; i < this.bytes.length / 4; i++) {

            store<u32>(bytes.dataStart + (i * 4), this.bytes[i]);
        }
        let result = "";

        // Skips zeros in the back to make the numbers readable without tons of zeros in front
        let backZeros = bytes.length - 1;

        while (backZeros >= 0 && bytes[backZeros--] == 0) {}

        // First digit could be still 0 so skip it
        let firstByte = bytes[++backZeros];
        if ((firstByte & 0xF0) == 0) {
            // Skips the hi byte if the first character of the output base16 would be `0`
            // This way the hex string wouldn't be something like "01"
            result += HEX_LOWERCASE[firstByte & 0x0F];
        }
        else {
            result += HEX_LOWERCASE[firstByte >> 4];
            result += HEX_LOWERCASE[firstByte & 0x0F];
        }

        // Convert the rest of bytes into base16
        for (let i = backZeros - 1; i >= 0; i--) {
            let value = bytes[i];
            result += HEX_LOWERCASE[value >> 4];
            result += HEX_LOWERCASE[value & 0x0F];
        }
        return result;
    }

    toString(): String {
        return this.toHex();
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
