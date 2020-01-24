import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode, PosErrorCode} from "../../../../contract-as/assembly/error";
import {PurseId} from "../../../../contract-as/assembly/purseid";
import {U512} from "../../../../contract-as/assembly/bignum";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {Key} from "../../../../contract-as/assembly/key";

const POS_ACTION = "bond";

export function call(): void {
    let proofOfStake = CL.getSystemContract(CL.SystemContract.ProofOfStake);
    if (proofOfStake === null) {
        Error.fromErrorCode(ErrorCode.InvalidSystemContract).revert();
        return;
    }

    let mainPurse = PurseId.getMainPurse();
    if (mainPurse === null) {
        Error.fromErrorCode(ErrorCode.MissingArgument).revert();
        return;
    }

    let bondingPurse = PurseId.createPurse();
    if (bondingPurse === null) {
        Error.fromErrorCode(ErrorCode.PurseNotCreated).revert();
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

    let ret = mainPurse.transferToPurse(
        <PurseId>(bondingPurse),
        amount,
    );
    if (ret > 0) {
        Error.fromErrorCode(ErrorCode.Transfer).revert();
        return;
    }

    let bondingPurseValue = CLValue.fromURef(bondingPurse.asURef());
    let key = Key.fromURef(proofOfStake);
    let args: CLValue[] = [
        CLValue.fromString(POS_ACTION),
        CLValue.fromU512(<U512>amount),
        bondingPurseValue
    ];
    let output = CL.callContract(key, args);
    if (output === null) {
        Error.fromPosErrorCode(PosErrorCode.BondTransferFailed).revert();
        return;
    }
}
