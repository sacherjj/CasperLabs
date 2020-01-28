export class Unit{
    toBytes(): Array<u8> {
        return  new Array<u8>(0);
    }

    static fromBytes(bytes: Uint8Array): Unit{
        return new Unit();
    }
}
