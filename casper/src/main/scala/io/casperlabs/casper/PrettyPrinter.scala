package io.casperlabs.casper

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.state.Key.URef.AccessRights
import io.casperlabs.casper.protocol._
import io.casperlabs.crypto.codec._
import io.casperlabs.ipc._
import io.casperlabs.casper.consensus.state._

object PrettyPrinter {

  def buildStringNoLimit(b: ByteString): String = Base16.encode(b.toByteArray)

  def buildString(k: Key): String = k.value match {
    case Key.Value.Empty                         => "KeyEmpty"
    case Key.Value.Address(Key.Address(address)) => s"Address(${buildString(address)})"
    case Key.Value.Uref(Key.URef(id, accessRights)) =>
      s"URef(${buildString(id)}, ${buildString(accessRights)})"
    case Key.Value.Hash(Key.Hash(hash)) => s"Hash(${buildString(hash)})"
    case Key.Value.Local(Key.Local(hash)) =>
      s"Local(${buildString(hash)})"
  }

  def buildString(t: Transform): String = t.transformInstance match {
    case Transform.TransformInstance.Empty                        => "TransformEmpty"
    case Transform.TransformInstance.AddI32(TransformAddInt32(i)) => s"Add($i)"
    case Transform.TransformInstance.AddBigInt(TransformAddBigInt(value)) =>
      s"AddBigInt(${value.get.value})"
    case Transform.TransformInstance.AddKeys(TransformAddKeys(ks)) =>
      s"Insert(${ks.map(buildString).mkString(",")})"
    case Transform.TransformInstance.Failure(_)  => "TransformFailure"
    case Transform.TransformInstance.Identity(_) => "Read"
    case Transform.TransformInstance.Write(TransformWrite(mv)) =>
      mv match {
        case None    => "Write(Nothing)"
        case Some(v) => s"Write(${buildString(v)})"
      }
  }

  def buildString(v: Option[ProtocolVersion]): String = v match {
    case None          => "No protocol version"
    case Some(version) => s"${version}"
  }

  def buildString(nk: NamedKey): String = nk match {
    case NamedKey(_, None)         => "EmptyNamedKey"
    case NamedKey(name, Some(key)) => s"NamedKey($name, ${buildString(key)})"
  }

  def buildString(v: Value): String = v.value match {
    case Value.Value.Empty => "ValueEmpty"
    case Value.Value.Account(
        Account(
          pk,
          nonce,
          urefs,
          purseId,
          associatedKeys,
          actionThresholds,
          accountActivity
        )
        ) =>
      s"Account(${buildString(pk)}, $nonce, {${urefs.map(buildString).mkString(",")}}, ${purseId
        .map(buildString)}, {${associatedKeys
        .map(buildString)
        .mkString(",")}, {${actionThresholds.map(buildString)}}, {${accountActivity.map(buildString)})"
    case Value.Value.BytesValue(bytes) => s"ByteArray(${buildString(bytes)})"
    case Value.Value.Contract(Contract(body, urefs, protocolVersion)) =>
      s"Contract(${buildString(body)}, {${urefs.map(buildString).mkString(",")}}, ${buildString(protocolVersion)})"
    case Value.Value.IntList(IntList(list))       => s"List(${list.mkString(",")})"
    case Value.Value.IntValue(i)                  => s"Int32($i)"
    case Value.Value.NamedKey(nk)                 => buildString(nk)
    case Value.Value.StringList(StringList(list)) => s"List(${list.mkString(",")})"
    case Value.Value.StringValue(s)               => s"String($s)"
    case Value.Value.BigInt(v)                    => s"BigInt(${v.value})"
    case Value.Value.Key(key)                     => buildString(key)
  }

  def buildString(b: BlockMessage): String =
    buildString(LegacyConversions.toBlock(b))

  def buildString(b: consensus.Block): String = {
    val blockString = for {
      header     <- b.header
      mainParent <- header.parentHashes.headOption
      postState  <- header.state
    } yield s"Block #${header.rank} (${buildString(b.blockHash)}) " +
      s"-- Sender ID ${buildString(header.validatorPublicKey)} " +
      s"-- M Parent Hash ${buildString(mainParent)} " +
      s"-- Contents ${buildString(postState.postStateHash)}" +
      s"-- Chain ID ${limit(header.chainId, 10)}"
    blockString match {
      case Some(str) => str
      case None      => s"Block with missing elements (${buildString(b.blockHash)})"
    }
  }

  private def limit(str: String, maxLength: Int): String =
    if (str.length > maxLength) {
      str.substring(0, maxLength) + "..."
    } else {
      str
    }

  def buildString(b: ByteString): String =
    limit(Base16.encode(b.toByteArray), 10)

  private def buildString(a: Key.URef.AccessRights): String =
    a match {
      case AccessRights.UNKNOWN        => "Unknown"
      case AccessRights.READ           => "Read"
      case AccessRights.ADD            => "Add"
      case AccessRights.WRITE          => "Write"
      case AccessRights.ADD_WRITE      => "AddWrite"
      case AccessRights.READ_ADD       => "ReadAdd"
      case AccessRights.READ_WRITE     => "ReadWrite"
      case AccessRights.READ_ADD_WRITE => "ReadAddWrite"
      case AccessRights.Unrecognized(value) =>
        s"Unrecognized AccessRights variant: $value"
    }

  private def buildString(uref: Key.URef): String =
    s"URef(${buildString(uref.uref)}, ${buildString(uref.accessRights)})"

  private def buildString(ak: Account.AssociatedKey): String = {
    val pk     = buildString(ak.publicKey)
    val weight = ak.weight
    s"$pk:$weight"
  }

  private def buildString(at: Account.ActionThresholds): String =
    s"Deployment threshold ${at.deploymentThreshold}, Key management threshold: ${at.keyManagementThreshold}"

  private def buildString(ac: Account.AccountActivity): String =
    s"Last deploy: ${ac.deploymentLastUsed}, last key management change: ${ac.keyManagementLastUsed}, inactivity period limit: ${ac.inactivityPeriodLimit}"

  def buildString(d: consensus.Deploy): String =
    s"Deploy ${buildStringNoLimit(d.deployHash)} (${buildStringNoLimit(d.getHeader.accountPublicKey)} / ${d.getHeader.nonce})"

  def buildString(d: DeployData): String =
    s"Deploy #${d.timestamp}"
}
