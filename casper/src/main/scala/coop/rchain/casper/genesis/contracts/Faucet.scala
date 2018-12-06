package coop.rchain.casper.genesis.contracts

/**
  * The purpose of a "Faucet" is to give a place where users
  * can obtain REV for testing their contracts for free on the
  * testnet. Since this is only applicable to testnet, the
  * `noopFaucet` will be used in main net, where there is no
  * way to obtain REV for free.
  */
object Faucet {
  //TODO: use registry instead of public names
  def basicWalletFaucet(mintName: String): String = ???

  val noopFaucet: String => String = ???
}
