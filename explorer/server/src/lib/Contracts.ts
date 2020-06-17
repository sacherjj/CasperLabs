import { Deploy } from "casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb";
import { Args, ByteArray } from "casperlabs-sdk";

export class CallFaucet {
  public static args(accountPublicKey: ByteArray, amount: bigint): Deploy.Arg[] {
    return Args.Args(
      ["target", Args.BytesValue(accountPublicKey)],
      ["amount", Args.BigIntValue(amount)]
    );
  }
}

export class StoredFaucet {
  public static args(): Deploy.Arg[] {
    return [];
  }
}
