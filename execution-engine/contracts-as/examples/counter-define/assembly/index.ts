import {Error, ErrorCode} from "../../../../contract-ffi-as/assembly/error";
import {putKey, getKey, getArg, callContract, storeFunctionAtHash, ret} from "../../../../contract-ffi-as/assembly";
import {Key} from "../../../../contract-ffi-as/assembly/key";
import {CLValue} from "../../../../contract-ffi-as/assembly/clvalue";
import {serializeArguments, fromBytesU64, toBytesString, toBytesMap, toBytesPair} from "../../../../contract-ffi-as/assembly/bytesrepr";
import {fromBytesString} from "../../../../contract-ffi-as/assembly/bytesrepr";
import {U512} from "../../../../contract-ffi-as/assembly/bignum";

const COUNT_KEY = "count";
const COUNTER_EXT = "counter_ext";
const COUNTER_KEY = "counter";
const GET_METHOD = "get";
const INC_METHOD = "inc";

enum Arg {
    UnknownMethodName = 0,
}

export function counter_ext(): void {
    const countKey = getKey(COUNTER_KEY);
    if (countKey === null) {
        Error.fromErrorCode(ErrorCode.GetKey).revert();
        return;
    }

    const methodNameBytes = getArg(Arg.UnknownMethodName);
    if (methodNameBytes === null) {
        Error.fromErrorCode(ErrorCode.MissingArgument).revert();
        return;
    }

    const methodName = fromBytesString(methodNameBytes);
    if (methodName === null) {
        Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
        return;
    }

    if (methodName == INC_METHOD) {
        const oneValue = new U512(<U64>1);
        const one = CLValue.fromU512(oneValue);
        countKey.add(one);
    }
    else if (methodName == GET_METHOD) {
        const valueBytes = countKey.read();
        if (valueBytes === null) {
            Error.fromUserError(3).revert();
            return;
        }

        let valueU512 = U512.fromBytes(valueBytes);
        if (valueU512 === null) {
            Error.fromUserError(4).revert();
            return;
        }
        
        let returnValue = CLValue.fromU512(valueU512);
        ret(returnValue);
    }
    else {
        Error.fromUserError(0).revert();
    }
}

export function call(): void {
    let initValue = new U512(<U64>123);
    let init = CLValue.fromU512(initValue);
    const maybeCounterLocalKey = Key.newInitialized(init);
    if (maybeCounterLocalKey === null) {
        Error.fromUserError(1).revert();
        return;
    }
    const counterLocalKey = <Key>maybeCounterLocalKey;

    let namedKeys = toBytesMap([
        toBytesPair(toBytesString(COUNT_KEY), counterLocalKey.toBytes()),
    ]);
    var pointer = storeFunctionAtHash(COUNTER_EXT, namedKeys);
    if (pointer === null) {
        Error.fromUserError(2).revert();
        return;
    }

    putKey(COUNTER_KEY, pointer);
}
