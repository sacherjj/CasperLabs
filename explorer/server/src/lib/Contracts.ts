import { Deploy } from "casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb";
import { Args, ByteArray } from "casperlabs-sdk";

export class Faucet {
  public static args(accountPublicKeyHash: ByteArray, amount: bigint): Deploy.Arg[] {
    return Args.Args(
      ["account", Args.BytesValue(accountPublicKeyHash)],
      ["amount", Args.BigIntValue(amount)]
    );
  }
}
