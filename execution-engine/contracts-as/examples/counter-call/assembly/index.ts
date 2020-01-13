import {Error, ErrorCode} from "../../../../contract-ffi-as/assembly/error";
import {getKey, callContract} from "../../../../contract-ffi-as/assembly";
import {CLValue} from "../../../../contract-ffi-as/assembly/clvalue";
import {Key} from "../../../../contract-ffi-as/assembly/key";
import {serializeArguments} from "../../../../contract-ffi-as/assembly/bytesrepr";

const COUNTER_KEY = "counter";
const GET_METHOD = "get";
const INC_METHOD = "inc";

export function call(): void {
    let counterKey = getKey(COUNTER_KEY);
    if (counterKey == null) {
        Error.fromErrorCode(ErrorCode.GetKey).revert();
    }
    
    callContract(<Key>counterKey, [
        CLValue.fromString(INC_METHOD),
    ]);

    callContract(<Key>counterKey, [
        CLValue.fromString(GET_METHOD),
    ]);
}
