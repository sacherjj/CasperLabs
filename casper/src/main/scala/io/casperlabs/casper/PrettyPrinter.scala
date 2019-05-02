package io.casperlabs.casper

import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol._
import io.casperlabs.ipc
import scalapb.GeneratedMessage
import io.casperlabs.crypto.codec._

object PrettyPrinter {

  def buildStringNoLimit(b: ByteString): String = Base16.encode(b.toByteArray)

  def buildString(t: GeneratedMessage): String =
    t match {
      case b: BlockMessage  => buildString(b)
      case d: DeployData    => buildString(d)
      case k: ipc.Key       => buildString(k)
      case t: ipc.Transform => buildString(t)
      case v: ipc.Value     => buildString(v)
      case _                => "Unknown consensus protocol message"
    }

  private def buildString(k: ipc.Key): String = k.keyInstance match {
    case ipc.Key.KeyInstance.Empty                            => "KeyEmpty"
    case ipc.Key.KeyInstance.Account(ipc.KeyAddress(address)) => s"Address(${buildString(address)})"
    case ipc.Key.KeyInstance.Uref(ipc.KeyURef(id))            => s"URef(${buildString(id)})"
    case ipc.Key.KeyInstance.Hash(ipc.KeyHash(hash))          => s"Hash(${buildString(hash)})"
  }

  private def buildString(t: ipc.Transform): String = t.transformInstance match {
    case ipc.Transform.TransformInstance.Empty                            => "TransformEmpty"
    case ipc.Transform.TransformInstance.AddI32(ipc.TransformAddInt32(i)) => s"Add($i)"
    case ipc.Transform.TransformInstance.AddKeys(ipc.TransformAddKeys(ks)) =>
      s"Insert(${ks.map(buildString).mkString(",")})"
    case ipc.Transform.TransformInstance.Failure(_)  => "TransformFailure"
    case ipc.Transform.TransformInstance.Identity(_) => "Identity"
    case ipc.Transform.TransformInstance.Write(ipc.TransformWrite(mv)) =>
      mv match {
        case None    => "Write(Nothing)"
        case Some(v) => s"Write(${buildString(v)})"
      }
  }

  private def buildString(nk: ipc.NamedKey): String = nk match {
    case ipc.NamedKey(_, None)         => "EmptyNamedKey"
    case ipc.NamedKey(name, Some(key)) => s"NamedKey($name, ${buildString(key)})"
  }

  private def buildString(v: ipc.Value): String = v.valueInstance match {
    case ipc.Value.ValueInstance.Empty => "ValueEmpty"
    case ipc.Value.ValueInstance.Account(ipc.Account(pk, nonce, urefs)) =>
      s"Account(${buildString(pk)}, $nonce, {${urefs.map(buildString).mkString(",")}})"
    case ipc.Value.ValueInstance.ByteArr(bytes) => s"ByteArray(${buildString(bytes)})"
    case ipc.Value.ValueInstance.Contract(ipc.Contract(body, urefs, protocolVersion)) =>
      s"Contract(${buildString(body)}, {${urefs.map(buildString).mkString(",")}}, ${protocolVersion})"
    case ipc.Value.ValueInstance.IntList(ipc.IntList(list))       => s"List(${list.mkString(",")})"
    case ipc.Value.ValueInstance.Integer(i)                       => s"Int32($i)"
    case ipc.Value.ValueInstance.NamedKey(nk)                     => buildString(nk)
    case ipc.Value.ValueInstance.StringList(ipc.StringList(list)) => s"List(${list.mkString(",")})"
    case ipc.Value.ValueInstance.StringVal(s)                     => s"String($s)"
  }

  private def buildString(b: BlockMessage): String = {
    val blockString = for {
      header     <- b.header
      mainParent <- header.parentsHashList.headOption
      body       <- b.body
      postState  <- body.state
    } yield
      s"Block #${postState.blockNumber} (${buildString(b.blockHash)}) " +
        s"-- Sender ID ${buildString(b.sender)} " +
        s"-- M Parent Hash ${buildString(mainParent)} " +
        s"-- Contents ${buildString(postState)}" +
        s"-- Shard ID ${limit(b.shardId, 10)}"
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

  private def buildString(d: DeployData): String =
    s"Deploy #${d.timestamp}"

  private def buildString(r: RChainState): String =
    buildString(r.postStateHash)
}
