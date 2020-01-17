const OPTION_TAG_NONE: u8 = 0;
const OPTION_TAG_SOME: u8 = 1;

// TODO: explore Option<T> (without interfaces to constrain T with, is it practical?)
export class Option{
    private bytes: Uint8Array | null;

    constructor(bytes: Uint8Array | null) {
        this.bytes = bytes;
    }

    isNone(): bool{
        return this.bytes === null;
    }

    isSome() : bool{
        return this.bytes != null;
    }

    unwrap(): Uint8Array | null{
        return this.bytes;
    }

    toBytes(): Array<u8>{
        if (this.bytes === null){
            let result = new Array<u8>(1);
            result[0] = OPTION_TAG_NONE;
            return result;
        }
        const bytes = <Uint8Array>this.bytes;

        let result = new Array<u8>(bytes.length + 1);
        result[0] = OPTION_TAG_SOME;
        for (let i = 0; i < bytes.length; i++) {
            result[i+1] = bytes[i];
        }

        return result;
    }

    static fromBytes(bytes: Uint8Array): Option{
        // check SOME / NONE flag at head
        // TODO: what if length is exactly 1?
        if (bytes.length >= 1 && bytes[0] == 1)
            return new Option(bytes.subarray(1));

        return new Option(null);
    }
}
