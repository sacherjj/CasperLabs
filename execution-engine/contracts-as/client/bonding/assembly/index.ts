import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode, PosErrorCode} from "../../../../contract-as/assembly/error";
import {U512} from "../../../../contract-as/assembly/bignum";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {Key} from "../../../../contract-as/assembly/key";
import {getMainPurse} from "../../../../contract-as/assembly/account";
import {createPurse, transferFromPurseToPurse} from "../../../../contract-as/assembly/purse";

const POS_ACTION = "bond";

export function call(): void {
    let proofOfStake = CL.getSystemContract(CL.SystemContract.ProofOfStake);
    if (proofOfStake === null) {
        Error.fromErrorCode(ErrorCode.InvalidSystemContract).revert();
        return;
    }

    let mainPurse = getMainPurse();
    if (mainPurse === null) {
        Error.fromErrorCode(ErrorCode.MissingArgument).revert();
        return;
    }

    let bondingPurse = createPurse();
    if (bondingPurse === null) {
        Error.fromErrorCode(ErrorCode.PurseNotCreated).revert();
        return;
    }

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

    let ret = transferFromPurseToPurse(
        mainPurse,
        bondingPurse,
        amount,
    );
    if (ret > 0) {
        Error.fromErrorCode(ErrorCode.Transfer).revert();
        return;
    }

    let bondingPurseValue = CLValue.fromURef(bondingPurse);
    let key = Key.fromURef(proofOfStake);
    let args: CLValue[] = [
        CLValue.fromString(POS_ACTION),
        CLValue.fromU512(amount),
        bondingPurseValue
    ];
    let output = CL.callContract(key, args);
    if (output === null) {
        Error.fromPosErrorCode(PosErrorCode.BondTransferFailed).revert();
        return;
    }
}
