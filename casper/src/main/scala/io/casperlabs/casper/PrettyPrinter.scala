package io.casperlabs.casper

import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol._
import io.casperlabs.crypto.codec._
import io.casperlabs.ipc

object PrettyPrinter {

  def buildStringNoLimit(b: ByteString): String = Base16.encode(b.toByteArray)

  def buildString(k: ipc.Key): String = k.keyInstance match {
    case ipc.Key.KeyInstance.Empty                            => "KeyEmpty"
    case ipc.Key.KeyInstance.Account(ipc.KeyAddress(address)) => s"Address(${buildString(address)})"
    case ipc.Key.KeyInstance.Uref(ipc.KeyURef(id, accessRights)) =>
      s"URef(${buildString(id)}, ${buildString(accessRights)})"
    case ipc.Key.KeyInstance.Hash(ipc.KeyHash(hash)) => s"Hash(${buildString(hash)})"
    case ipc.Key.KeyInstance.Local(ipc.KeyLocal(seed, keyHash)) =>
      s"Local(${buildString(seed)}, ${buildString(keyHash)})"
  }

  def buildString(t: ipc.Transform): String = t.transformInstance match {
    case ipc.Transform.TransformInstance.Empty                            => "TransformEmpty"
    case ipc.Transform.TransformInstance.AddI32(ipc.TransformAddInt32(i)) => s"Add($i)"
    case ipc.Transform.TransformInstance.AddBigInt(ipc.TransformAddBigInt(value)) =>
      s"AddBigInt(${value.get.value})"
    case ipc.Transform.TransformInstance.AddKeys(ipc.TransformAddKeys(ks)) =>
      s"Insert(${ks.map(buildString).mkString(",")})"
    case ipc.Transform.TransformInstance.Failure(_)  => "TransformFailure"
    case ipc.Transform.TransformInstance.Identity(_) => "Read"
    case ipc.Transform.TransformInstance.Write(ipc.TransformWrite(mv)) =>
      mv match {
        case None    => "Write(Nothing)"
        case Some(v) => s"Write(${buildString(v)})"
      }
  }

  def buildString(v: Option[ipc.ProtocolVersion]): String = v match {
    case None          => "No protocol version"
    case Some(version) => s"${version}"
  }

  def buildString(nk: ipc.NamedKey): String = nk match {
    case ipc.NamedKey(_, None)         => "EmptyNamedKey"
    case ipc.NamedKey(name, Some(key)) => s"NamedKey($name, ${buildString(key)})"
  }

  def buildString(v: ipc.Value): String = v.valueInstance match {
    case ipc.Value.ValueInstance.Empty => "ValueEmpty"
    case ipc.Value.ValueInstance.Account(
        ipc.Account(
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
    case ipc.Value.ValueInstance.ByteArr(bytes) => s"ByteArray(${buildString(bytes)})"
    case ipc.Value.ValueInstance.Contract(ipc.Contract(body, urefs, protocolVersion)) =>
      s"Contract(${buildString(body)}, {${urefs.map(buildString).mkString(",")}}, ${buildString(protocolVersion)})"
    case ipc.Value.ValueInstance.IntList(ipc.IntList(list))       => s"List(${list.mkString(",")})"
    case ipc.Value.ValueInstance.Integer(i)                       => s"Int32($i)"
    case ipc.Value.ValueInstance.NamedKey(nk)                     => buildString(nk)
    case ipc.Value.ValueInstance.StringList(ipc.StringList(list)) => s"List(${list.mkString(",")})"
    case ipc.Value.ValueInstance.StringVal(s)                     => s"String($s)"
    case ipc.Value.ValueInstance.BigInt(v)                        => s"BigInt(${v.value})"
    case ipc.Value.ValueInstance.Key(key)                         => buildString(key)
  }

  def buildString(b: BlockMessage): String =
    buildString(LegacyConversions.toBlock(b))

  def buildString(b: consensus.Block): String = {
    val blockString = for {
      header     <- b.header
      mainParent <- header.parentHashes.headOption
      postState  <- header.state
    } yield
      s"Block #${header.rank} (${buildString(b.blockHash)}) " +
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

  private def buildString(a: ipc.KeyURef.AccessRights): String =
    a match {
      case ipc.KeyURef.AccessRights.UNKNOWN        => "Unknown"
      case ipc.KeyURef.AccessRights.READ           => "Read"
      case ipc.KeyURef.AccessRights.ADD            => "Add"
      case ipc.KeyURef.AccessRights.WRITE          => "Write"
      case ipc.KeyURef.AccessRights.ADD_WRITE      => "AddWrite"
      case ipc.KeyURef.AccessRights.READ_ADD       => "ReadAdd"
      case ipc.KeyURef.AccessRights.READ_WRITE     => "ReadWrite"
      case ipc.KeyURef.AccessRights.READ_ADD_WRITE => "ReadAddWrite"
      case ipc.KeyURef.AccessRights.Unrecognized(value) =>
        s"Unrecognized AccessRights variant: $value"
    }

  private def buildString(uref: ipc.KeyURef): String =
    s"URef(${buildString(uref.uref)}, ${buildString(uref.accessRights)})"

  private def buildString(ak: ipc.Account.AssociatedKey): String = {
    val pk     = buildString(ak.pubKey)
    val weight = ak.weight
    s"$pk:$weight"
  }

  private def buildString(at: ipc.Account.ActionThresholds): String =
    s"Deployment threshold ${at.deploymentThreshold}, Key management threshold: ${at.keyManagementThreshold}"

  private def buildString(ac: ipc.Account.AccountActivity): String =
    s"Last deploy: ${ac.deploymentLastUsed}, last key management change: ${ac.keyManagementLastUsed}, inactivity period limit: ${ac.inactivityPeriodLimit}"

  def buildString(d: consensus.Deploy): String =
    s"Deploy ${buildStringNoLimit(d.deployHash)} (${buildStringNoLimit(d.getHeader.accountPublicKey)} / ${d.getHeader.nonce})"

  def buildString(d: DeployData): String =
    s"Deploy #${d.timestamp}"
}
