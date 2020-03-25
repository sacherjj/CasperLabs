import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {Key} from "../../../../contract-as/assembly/key";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {callContract, getKey, putKey} from "../../../../contract-as/assembly";

const HELLO_NAME_KEY = "hello_name";
const HELLO_WORLD_KEY = "helloworld";
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

    let outputMessageResult = fromBytesString(output);
    if (outputMessageResult.hasError()) {
        Error.fromUserError(2).revert();
        return;
    }
    let outputMessage = outputMessageResult.value;

    if (outputMessage != EXPECTED){
        Error.fromUserError(3).revert();
        return;
    }

    const key = Key.create(CLValue.fromString(outputMessage));
    if (key === null) {
        Error.fromUserError(4).revert();
        return;
    }

    putKey(HELLO_WORLD_KEY, <Key>key);
}
