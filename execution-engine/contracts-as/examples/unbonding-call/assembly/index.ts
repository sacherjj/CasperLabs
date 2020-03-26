import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {U512} from "../../../../contract-as/assembly/bignum";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {Key} from "../../../../contract-as/assembly/key";

const POS_ACTION = "unbond";

export function call(): void {
    let proofOfStake = CL.getSystemContract(CL.SystemContract.ProofOfStake);

    let amountBytes = CL.getArg(0);
    if (amountBytes === null) {
        Error.fromErrorCode(ErrorCode.MissingArgument).revert();
        return;
    }

    let amountResult = U512.fromBytes(amountBytes);
    if (amountResult.hasError()) {
        Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
        return;
    }
    let amount = amountResult.value;

    let key = Key.fromURef(proofOfStake);
    let args: CLValue[] = [
        CLValue.fromString(POS_ACTION),
        CLValue.fromU512(amount)
    ];
    CL.callContract(key, args);
}
