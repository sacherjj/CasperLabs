import {toBytesU64} from "./bytesrepr";
import {Pair} from "./pair";

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

export class U512 {
    private pn: Uint32Array;

    constructor() {
        this.pn = new Uint32Array(16);
    }

    static fromHex(hex: String): U512 {
        let res = new U512();
        res.setHex(hex);
        return res;
    }

    static fromU64(value: u64): U512 {
        let res = new U512();
        res.setU64(value);
        return res;
    }

    // Gets width in bytes
    get width(): i32 {
        return this.pn.length * 4;
    }

    setU64(value: u64): void {
        this.pn.fill(0);
        assert(this.pn.length >= 2);
        this.pn[0] = <u32>(value & <u64>0xffffffff);
        this.pn[1] = <u32>(value >> 32);
    }

    setHex(value: String): void {
        if (value.length >= 2 && value[0] == '0' && (value[1] == 'x' || value[1] == 'X'))
            value = value.substr(2);

        // Find the length
        let digits = 0;
        while (digits < value.length && HEX_DIGITS[<usize>value.charCodeAt(digits)] != -1 ) {
            digits++;
        }

        // Decodes hex string into an array of bytes
        let bytes = new Uint8Array(this.width);
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
        this.setBytesLE(bytes);
    }

    isZero(): bool {
        for (let i = 0; i < this.pn.length; i++) {
            if (this.pn[i] != 0) {
                return false;
            }
        }
        return true;
    }

    @operator("+")
    add(other: U512): U512 {
        assert(this.pn.length == other.pn.length);
        let carry = <u64>0;
        for (let i = 0; i < this.pn.length; i++) {
            let n = carry + <u64>this.pn[i] + <u64>other.pn[i];
            this.pn[i] = <u32>(n & <u64>0xffffffff);
            carry = <u64>(n >> 32);
        }
        return this;
    }

    @operator.prefix("-")
    neg(): U512 {
        let ret = new U512();
        for (let i = 0; i < this.pn.length; i++) {
            ret.pn[i] = ~this.pn[i];
        }
        ++ret;
        return ret;
    }

    @operator("-")
    sub(other: U512): U512 {
        return this.add(-other);
    }

    @operator("*")
    mul(other: U512): U512 {
        assert(this.pn.length == other.pn.length);
        let ret = new U512();

        for (let j = 0; j < this.pn.length; j++) {
            let carry: u64 = <u64>0;
            for (let i = 0; i + j < this.pn.length; i++) {
                let n: u64 = carry + <u64>ret.pn[i + j] + <u64>this.pn[j] * <u64>other.pn[i];
                ret.pn[i + j] = <u32>(n & <u64>0xffffffff);
                carry = <u64>(n >> 32);
            }
        }

        return ret;
    }

    private increment(): void {
        let i = 0;
        while (i < this.pn.length && ++this.pn[i] == 0) {
            i++;
        }
    }

    private decrement(): void {
        let i = 0;
        while (i < this.pn.length && --this.pn[i] == u32.MAX_VALUE) {
            i++;
        }
    }

    @operator.prefix("++")
    prefixInc(): U512 {
        this.increment();
        return this;
    }

    @operator.prefix("--")
    prefixDec(): U512 {
        this.decrement();
        return this;
    }

    @operator.postfix("++")
    postfixInc(): U512 {
        let cloned = this.clone();
        cloned.increment();
        return cloned;
    }

    @operator.postfix("--")
    postfixDec(): U512 {
        let cloned = this.clone();
        cloned.decrement();
        return cloned;
    }

    setValues(pn: Uint32Array): void {
        for (let i = 0; i < this.pn.length; i++) {
            this.pn[i] = pn[i];
        }
    }

    clone(): U512 {
        let U512ber = new U512();
        U512ber.setValues(this.pn);
        return U512ber;
    }

    // Returns bits length
    bits(): u32 {
        for (let i = this.pn.length - 1; i >= 0; i--) {
            if (this.pn[i] > 0) {
                return 32 * i + (32 - clz(this.pn[i]));
            }
        }
        return 0;
    }

    divMod(other: U512): Pair<U512, U512> | null {
        assert(this.pn.length == other.pn.length);

        let div = other.clone(); // make a copy, so we can shift.
        let num = this.clone(); // make a copy, so we can subtract the quotient.

        let res = new U512();

        let num_bits = num.bits();
        let div_bits = div.bits();

        if (div_bits == 0) {
            // division by zero
            return null;
        }

        if (div_bits > num_bits) {
            // the result is certainly 0 and rem is the lhs of equation.
            let zero = new U512();
            return new Pair<U512, U512>(zero, num);
        }

        let shift: i32 = num_bits - div_bits;
        div <<= shift; // shift so that div and num align.

        while (shift >= 0) {
            if (num >= div) {
                num -= div;
                res.pn[shift / 32] |= (1 << (shift & 31)); // set a bit of the result.
            }
            div >>= 1; // shift back.
            shift--;
        }
        // num now contains the remainder of the division.
        return new Pair<U512, U512>(res, num);
    }

    @operator("/")
    div(other: U512): U512 {
        let divModResult = this.divMod(other);
        assert(divModResult !== null);
        return divModResult.first;
    }

    @operator("%")
    rem(other: U512): U512 {
        let divModResult = this.divMod(other);
        assert(divModResult !== null);
        return divModResult.second;
    }

    @operator("<<")
    shl(shift: u32): U512 {
        let res = new U512();

        let k: u32 = shift / 32;
        shift = shift % 32;

        for (let i = 0; i < this.pn.length; i++) {
            if (i + k + 1 < this.pn.length && shift != 0)
                res.pn[i + k + 1] |= (this.pn[i] >> (32 - shift));
            if (i + k < this.pn.length)
                res.pn[i + k] |= (this.pn[i] << shift);
        }

        return res;
    }

    @operator(">>")
    shr(shift: u32): U512 {
        let res = new U512();

        let k = shift / 32;
        shift = shift % 32;

        for (let i = 0; i < this.pn.length; i++) {
            if (i - k - 1 >= 0 && shift != 0)
                res.pn[i - k - 1] |= (this.pn[i] << (32 - shift));
            if (i - k >= 0)
                res.pn[i - k] |= (this.pn[i] >> shift);
        }

        return res;
    }

    cmp(other: U512): i32 {
        assert(this.pn.length == other.pn.length);
        for (let i = this.pn.length - 1; i >= 0; --i) {
            if (this.pn[i] < other.pn[i]) {
                return -1;
            }
            if (this.pn[i] > other.pn[i]) {
                return 1;
            }
        }
        return 0;
    }

    @operator("==")
    eq(other: U512): bool {
        return this.cmp(other) == 0;
    }

    @operator("!=")
    neq(other: U512): bool {
        return this.cmp(other) != 0;
    }

    @operator(">")
    gt(other: U512): bool {
        return this.cmp(other) == 1;
    }

    @operator("<")
    lt(other: U512): bool {
        return this.cmp(other) == -1;
    }

    @operator(">=")
    gte(other: U512): bool {
        return this.cmp(other) >= 0;
    }

    @operator("<=")
    lte(other: U512): bool {
        return this.cmp(other) <= 0;
    }

    toBytesLE(): Uint8Array {
        let bytes = new Uint8Array(this.width);
        // Copy array of u32 into array of u8
        for (let i = 0; i < this.pn.length; i++) {
            store<u32>(bytes.dataStart + (i * 4), this.pn[i]);
        }
        return bytes;
    }

    setBytesLE(bytes: Uint8Array): void {
        for (let i = 0; i < this.pn.length; i++) {
            let num = load<u32>(bytes.dataStart + (i * 4));
            this.pn[i] = num;
        }
    }

    private toHex(): String {
        let bytes = this.toBytesLE();
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

    static fromBytes(bytes: Uint8Array): U512 | null {
        if (bytes.length < 1) {
            return null;
        }

        const lengthPrefix = <i32>bytes[0];
        let res = new U512();

        let buffer = new Uint8Array(res.width); // no super calls?!
        for (let i = 0; i < lengthPrefix; i++) {
            buffer[i] = bytes[i + 1];
        }

        res.setBytesLE(buffer);
        return res;
    }

    toBytes(): Array<u8> {
        let bytes = this.toBytesLE();
        let skipZeros = bytes.length - 1;

        // Skip zeros at the end
        while (skipZeros >= 0 && bytes[skipZeros] == 0) {
            skipZeros--;
        }

        // Continue
        let lengthPrefix = skipZeros + 1;

        let result = new Array<u8>(1 + lengthPrefix);
        result[0] = <u8>lengthPrefix;
        for (let i = 0; i < lengthPrefix; i++) {
            result[1 + i] = bytes[i];
        }
        return result;
    }
};
