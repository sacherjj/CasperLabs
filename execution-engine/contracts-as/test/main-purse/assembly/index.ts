//@ts-nocheck
import {getMainPurse} from "../../../../contract-as/assembly/account";
import * as CL from "../../../../contract-as/assembly";
import {Error} from "../../../../contract-as/assembly/error";
import {PurseId} from "../../../../contract-as/assembly/purseid";

enum Args {
  ExpectedMainPurse = 0,
}

enum CustomError {
  MissingExpectedMainPurseArg = 86,
  InvalidExpectedMainPurseArg = 97,
  UnableToGetMainPurse = 108,
  EqualityAssertionFailed = 139
}

export function call(): void {
  let expectedMainPurseArg = CL.getArg(Args.ExpectedMainPurse);
  if (expectedMainPurseArg === null){
    Error.fromUserError(<u16>CustomError.MissingExpectedMainPurseArg).revert();
    return;
  }
  let expectedMainPurse = PurseId.fromBytes(expectedMainPurseArg);
  if (expectedMainPurse === null){
    Error.fromUserError(<u16>CustomError.InvalidExpectedMainPurseArg).revert();
    return;
  }
  const actualMainPurse = getMainPurse();
  if (actualMainPurse === null){
    Error.fromUserError(<u16>CustomError.UnableToGetMainPurse).revert();
    return;
  }
  if (<PurseId>expectedMainPurse != <PurseId>actualMainPurse)
    Error.fromUserError(<u16>CustomError.EqualityAssertionFailed).revert();
}
