import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {putKey, getKey, hasKey, getArg, storeFunctionAtHash, ret} from "../../../../contract-as/assembly";
import {Key} from "../../../../contract-as/assembly/key";
import {CLValue, CLTypeTag} from "../../../../contract-as/assembly/clvalue";
import {toBytesString, toBytesMap, toBytesPair} from "../../../../contract-as/assembly/bytesrepr";
import {fromBytesString, fromBytesI32, fromBytesU32, fromBytesStringList} from "../../../../contract-as/assembly/bytesrepr";
import {U512} from "../../../../contract-as/assembly/bignum";
import {Option} from "../../../../contract-as/assembly/option";
import {arrayToTyped} from "../../../../contract-as/assembly/utils";

const LIST_KEY = "list";
const MAILING_KEY = "mailing";
const MAILING_LIST_EXT = "mailing_list_ext";

enum Arg {
    MethodName = 0,
    Arg1 = 1,
}

enum UserError {
    UnknownMethodName = 0,
}

function getListKey(name: String): Key {
    const key = getKey(name);
    if (key === null) {
        Error.fromErrorCode(ErrorCode.GetKey).revert();
        unreachable();
    }
    if (!(<Key>key).isURef()) {
        Error.fromErrorCode(ErrorCode.UnexpectedKeyVariant).revert();
        unreachable();
    }
    return <Key>key;
}

function updateList(name: String): void {
    const listKey = getListKey(LIST_KEY);
    const listBytes = listKey.read();
    if (listBytes === null) {
        Error.fromErrorCode(ErrorCode.Read).revert();
        return;
    }

    let lst = fromBytesStringList(<Uint8Array>listBytes);
    if (lst === null) {
        Error.fromErrorCode(ErrorCode.ValueNotFound).revert();
        return;
    }

    lst.push(name);

    listKey.write(CLValue.fromStringList(lst));
}

function sub(name: String): Key | null {
    if (hasKey(name)) {
        const initMessage = CLValue.fromStringList(["Hello again!"]);
        return Key.create(initMessage);
    }
    else {
        const initMessage = CLValue.fromStringList(["Welcome!"]);
        let newKey = Key.create(initMessage);
        if (newKey === null) {
            return null;
        }
        putKey(name, newKey);
        updateList(name);
        return newKey;
    }

}

function publish(msg: String): void {
    const listKey = getListKey(LIST_KEY);
    const listBytes = listKey.read();
    if (listBytes === null) {
        Error.fromErrorCode(ErrorCode.Read).revert();
        return;
    }

    let lst = fromBytesStringList(<Uint8Array>listBytes);
    if (lst === null) {
        Error.fromErrorCode(ErrorCode.ValueNotFound).revert();
        return;
    }

    for (let i = 0; i < lst.length; i++) {
        const name = lst[i];
        let key = getListKey(name);
        let messagesBytes = key.read();
        if (messagesBytes === null) {
            Error.fromErrorCode(ErrorCode.Read).revert();
            return;
        }
        let messages = fromBytesStringList(messagesBytes);
        if (messages === null) {
            Error.fromErrorCode(ErrorCode.ValueNotFound).revert();
            return;
        }
        messages.push(msg);
        key.write(CLValue.fromStringList(messages));
    }
}

export function mailing_list_ext(): void {
    let methodNameBytes = getArg(Arg.MethodName);
    if (methodNameBytes === null) {
        Error.fromErrorCode(ErrorCode.MissingArgument).revert();
        return;
    }

    let methodName = fromBytesString(methodNameBytes);
    if (methodName === null) {
        Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
        return;
    }

    let arg1Bytes = getArg(Arg.Arg1);
    if (arg1Bytes === null) {
        Error.fromErrorCode(ErrorCode.MissingArgument).revert();
        return;
    }

    let arg1 = fromBytesString(arg1Bytes);
    if (arg1 === null) {
        Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
        return;
    }

    if (methodName == "sub") {
        let maybeKey = sub(arg1);
        if (maybeKey === null) {
            let result = new Option(null);
            ret(CLValue.fromOption(result, CLTypeTag.Key));
            return;
        }

        let key = <Key>maybeKey;
        let keyBytes = key.toBytes();
        let opt = new Option(arrayToTyped(keyBytes));
        ret(CLValue.fromOption(opt, CLTypeTag.Key));
    }
    else if (methodName == "pub") {
        publish(arg1);
    }
    else {
        Error.fromUserError(<u16>UserError.UnknownMethodName).revert();
    }
}

export function call(): void {
    let init = CLValue.fromStringList([]);
    const maybeListKey = Key.create(init);
    if (maybeListKey === null) {
        Error.fromUserError(1).revert();
        return;
    }
    const counterLocalKey = <Key>maybeListKey;

    let namedKeys = toBytesMap([
        toBytesPair(toBytesString(LIST_KEY), counterLocalKey.toBytes()),
    ]);
    var pointer = storeFunctionAtHash(MAILING_LIST_EXT, namedKeys);
    if (pointer === null) {
        Error.fromUserError(2).revert();
        return;
    }

    putKey(MAILING_KEY, <Key>pointer);
}
