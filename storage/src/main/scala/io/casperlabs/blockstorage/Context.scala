package io.casperlabs.blockstorage
import java.nio.ByteBuffer
import java.nio.file.Path

import org.lmdbjava.{Env, EnvFlags}

object Context {

  def env(
      path: Path,
      mapSize: Long,
      flags: List[EnvFlags] = List(EnvFlags.MDB_NOTLS)
  ): Env[ByteBuffer] =
    Env
      .create()
      .setMapSize(mapSize)
      .setMaxDbs(8)
      .setMaxReaders(2048)
      .open(path.toFile, flags: _*)

}
