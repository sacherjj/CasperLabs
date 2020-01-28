import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {putKey, getKey, getArg, storeFunctionAtHash, ret} from "../../../../contract-as/assembly";
import {Key} from "../../../../contract-as/assembly/key";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {toBytesString, toBytesMap, toBytesPair} from "../../../../contract-as/assembly/bytesrepr";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";


const HELLO_NAME_EXT = "hello_name_ext";
const HELLO_NAME_KEY = "hello_name";

enum Arg {
    Name = 0,
}

export function hello_name_ext(): void {
    const key = getKey(HELLO_NAME_KEY);
    if (key === null) {
        Error.fromErrorCode(ErrorCode.GetKey).revert();
        return;
    }

    const nameBytes = getArg(Arg.Name);
    if (nameBytes === null) {
        Error.fromErrorCode(ErrorCode.MissingArgument).revert();
        return;
    }

    const name = fromBytesString(nameBytes);
    if (name === null) {
        Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
        return;
    }

    let hello = "Hello, " + name;

    let clValue = CLValue.fromString(<string>hello);
    if (clValue === null) {
        Error.fromUserError(1).revert();
        return;
    }

    ret(clValue);
}

export function call(): void {
    let init = CLValue.fromI32(0);
    const maybeCounterLocalKey = Key.create(init);
    if (maybeCounterLocalKey === null) {
        Error.fromUserError(1).revert();
        return;
    }
    const counterLocalKey = <Key>maybeCounterLocalKey;

    let namedKeys = toBytesMap([
        toBytesPair(toBytesString(HELLO_NAME_KEY), counterLocalKey.toBytes()),
    ]);
    var pointer = storeFunctionAtHash(HELLO_NAME_EXT, namedKeys);
    if (pointer === null) {
        Error.fromUserError(2).revert();
        return;
    }

    putKey(HELLO_NAME_KEY, <Key>pointer);
}
