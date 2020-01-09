import {Error} from "../../../../contract-ffi-as/assembly/error";

export function call(): void {
    let error = new Error(100);
    error.revert();
    return;
}
