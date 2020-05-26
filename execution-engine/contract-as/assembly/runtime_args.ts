import {CLValue} from "./clvalue";
import { Pair } from "./pair";
import { toBytesU32, toBytesString } from "./bytesrepr";

export class RuntimeArgs {
    constructor(public arguments: Array<Pair<String, CLValue>>) {}

    static fromArray(pairs: Pair<String, CLValue>[]): RuntimeArgs {
        return new RuntimeArgs(pairs);
    }

    toBytes(): Array<u8> {
        var bytes = toBytesU32(<u32>this.arguments.length);
        for (var i = 0; i < this.arguments.length; i++) {
            const argNameBytes = toBytesString(this.arguments[i].first);
            bytes = bytes.concat(argNameBytes);
            const argValueBytes = this.arguments[i].second.toBytes();
            bytes = bytes.concat(argValueBytes);
        }
        return bytes;
    }
}
