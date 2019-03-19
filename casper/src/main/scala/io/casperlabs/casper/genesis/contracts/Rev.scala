package io.casperlabs.casper.genesis.contracts

class Rev[A](
    rhoCode: A => String,
    wallets: Seq[A],
    faucetCode: String => String,
    posParams: ProofOfStakeParams
) {
  private val initialTotalBond = posParams.validators.foldLeft(0L) {
    case (acc, v) => acc + v.stake
  }
  private val initialBondsCode = ProofOfStake.initialBondsCode(posParams.validators)

  private val minimumBond = posParams.minimumBond
  private val maximumBond = posParams.maximumBond

  final val code = s"""
  """.stripMargin

  private[this] def walletCode: String =
    if (wallets.isEmpty) {
      "Nil"
    } else {
      wallets.map(rhoCode).mkString(" |\n")
    }
}

class PreWalletRev(
    wallets: Seq[PreWallet],
    faucetCode: String => String,
    posParams: ProofOfStakeParams
) extends Rev[PreWallet](PreWallet.rhoCode, wallets, faucetCode, posParams)
