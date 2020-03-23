package db.migration

import cats.effect.Blocker
import doobie.implicits._
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.util.DoobieCodecs
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock
import org.flywaydb.core.api.migration.{BaseJavaMigration, Context}

class V20200302_1143__Fill_create_time_millis extends BaseJavaMigration with DoobieCodecs {
  override def migrate(context: Context) = {
    val connection            = context.getConnection
    implicit val scheduler    = Scheduler(ExecutionContexts.synchronous)
    implicit val contextShift = Task.contextShift(scheduler)
    implicit val canBlock     = CanBlock.permit
    val xa = Transactor
      .fromConnection[Task](connection, Blocker.liftExecutionContext(ExecutionContexts.synchronous))

    val data = sql"SELECT block_hash, data FROM block_metadata"
      .query[(BlockHash, BlockSummary)]
      .stream
      .evalMap {
        case (hash, summary) =>
          val t = summary.getHeader.timestamp
          sql"UPDATE block_metadata SET create_time_millis=$t WHERE block_hash=$hash".update.run
      }
    data.transact(xa).compile.toList.runSyncUnsafe()
  }
}
