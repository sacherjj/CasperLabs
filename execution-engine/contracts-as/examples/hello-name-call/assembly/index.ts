import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {Key} from "../../../../contract-as/assembly/key";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {callContract, getKey} from "../../../../contract-as/assembly";

const HELLO_NAME_KEY = "hello_name";
const HELLOWORLD_KEY = "helloworld";
const WORLD = "World";
const EXPECTED = "Hello, World";

export function call(): void {
    let contractKey = getKey(HELLO_NAME_KEY);
    if (contractKey === null) {
        Error.fromErrorCode(ErrorCode.GetKey).revert();
        return;
    }

    let output = callContract(<Key>contractKey, [
        CLValue.fromString(WORLD),
    ]);

    if (output === null) {
        Error.fromUserError(1).revert();
        return;
    }
    let outputMessage = fromBytesString(output);
    if (outputMessage === null) {
        Error.fromUserError(2).revert();
        return;
    }

    if (outputMessage != EXPECTED){
        Error.fromUserError(3).revert();
        return;
    }

    // TODO: need a new_turef equivalent to store value and get uref
    // runtime::put_key(HELLOWORLD_KEY, storage::new_turef(result).into()); <-- rust version
}
