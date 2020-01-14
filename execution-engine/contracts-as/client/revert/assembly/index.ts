import {Error} from "../../../../contract-ffi-as/assembly/error";

export function call(): void {
    Error.fromUserError(100).revert();
}
