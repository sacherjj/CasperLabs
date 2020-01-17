import * as CL from "../../../../contract-ffi-as/assembly";
import {Error, ErrorCode, PosErrorCode} from "../../../../contract-ffi-as/assembly/error";
import {U512} from "../../../../contract-ffi-as/assembly/bignum";
import {CLValue} from "../../../../contract-ffi-as/assembly/clvalue";
import {Key} from "../../../../contract-ffi-as/assembly/key";

const POS_ACTION = "unbond";

export function call(): void {
    let proofOfStake = CL.getSystemContract(CL.SystemContract.ProofOfStake);
    if (proofOfStake === null) {
        Error.fromErrorCode(ErrorCode.InvalidSystemContract).revert();
        return;
    }

    let amountBytes = CL.getArg(0);
    if (amountBytes === null) {
        Error.fromErrorCode(ErrorCode.MissingArgument).revert();
        return;
    }

    let amount = U512.fromBytes(amountBytes);
    if (amount === null) {
        Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
        return;
    }

    let key = Key.fromURef(proofOfStake);
    let args: CLValue[] = [
        CLValue.fromString(POS_ACTION),
        CLValue.fromU512(<U512>amount)
    ];
    let output = CL.callContract(key, args);
    if (output === null) {
        Error.fromPosErrorCode(PosErrorCode.UnbondTransferFailed).revert();
        return;
    }
}
