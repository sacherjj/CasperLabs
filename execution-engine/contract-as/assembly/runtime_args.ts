import {CLValue} from "./clvalue";
import { Pair } from "./pair";
import { toBytesU32, toBytesString } from "./bytesrepr";

/**
 * Implements a collection of runtime arguments.
 */
export class RuntimeArgs {
    constructor(public arguments: Map<String, CLValue> = new Map<String, CLValue>()) {}

    static fromArray(pairs: Pair<String, CLValue>[]): RuntimeArgs {
        let map = new Map<String, CLValue>();
        for (let i = 0; i < pairs.length; i++) {
            map.set(pairs[i].first, pairs[i].second);
        }
        return new RuntimeArgs(map);
    }
    
    static fromMap(mapOfArgs: Map<String, CLValue>): RuntimeArgs {
        return new RuntimeArgs(mapOfArgs);
    }

    toBytes(): Array<u8> {
        let keys = this.arguments.keys();
        let values = this.arguments.values();
        let bytes = toBytesU32(<u32>keys.length);
        for (var i = 0; i < keys.length; i++) {
            const argNameBytes = toBytesString(keys[i]);
            bytes = bytes.concat(argNameBytes);
            const argValueBytes = values[i].toBytes();
            bytes = bytes.concat(argValueBytes);
        }
        return bytes;
    }
}
