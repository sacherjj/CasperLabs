//@ts-nocheck
import {Error} from "../../../../contract-as/assembly/error";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {arrayToTyped} from "../../../../contract-as/assembly/utils";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {addLocal, writeLocal} from "../../../../contract-as/assembly/local";
import * as CL from "../../../../contract-as/assembly";

const CMD_WRITE = "write";
const CMD_ADD = "add";

const INITIAL_VALUE: u64 = 10;
const ADD_VALUE: u64 = 5;

enum Args{
  Command = 0
}

enum CustomError {
  MissingCommandArg = 0,
  InvalidCommandArg = 1
}

export function call(): void {
  const localBytes = new Array<u8>(32);
  localBytes.fill(66);
  const local = arrayToTyped(localBytes);

  const commandArg = CL.getArg(Args.Command);
  if (commandArg === null) {
    Error.fromUserError(<u16>CustomError.MissingCommandArg).revert();
    return;
  }
  const commandResult = fromBytesString(commandArg);
  if (commandResult.hasError()) {
    Error.fromUserError(<u16>CustomError.InvalidCommandArg).revert();
    return;
  }
  const command = commandResult.value;
  if (command == CMD_WRITE){
    writeLocal(local, CLValue.fromU64(INITIAL_VALUE));
  } else if (command == CMD_ADD){
    addLocal(local, CLValue.fromU64(ADD_VALUE));
  }
}
