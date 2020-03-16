/**
 * A class representing the unit type, i.e. a type that has no values (equivalent to eg. `void` in
 * C or `()` in Rust).
 */
export class Unit{
    toBytes(): Array<u8> {
        return new Array<u8>(0);
    }

    static fromBytes(bytes: Uint8Array): Unit{
        return new Unit();
    }
}
