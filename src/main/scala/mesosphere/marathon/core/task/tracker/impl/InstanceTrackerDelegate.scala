package mesosphere.marathon
package core.task.tracker.impl

import java.util.concurrent.TimeoutException

import akka.actor.ActorRef
import akka.pattern.ask
import akka.pattern.AskTimeoutException
import akka.util.Timeout
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, InstanceTrackerConfig }
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.marathon.state.PathId

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

/**
  * Provides a [[InstanceTracker]] interface to [[InstanceTrackerActor]].
  *
  * This is used for the "global" TaskTracker trait and it is also
  * is used internally in this package to communicate with the TaskTracker.
  *
  * @param metrics a metrics object if we want to track metrics for this delegate. We only want to track
  *                metrics for the "global" TaskTracker.
  */
private[tracker] class InstanceTrackerDelegate(
    metrics: Option[Metrics],
    config: InstanceTrackerConfig,
    taskTrackerRef: ActorRef) extends InstanceTracker {

  override def instancesBySpecSync: InstanceTracker.InstancesBySpec = {
    import ExecutionContext.Implicits.global
    Await.result(instancesBySpec(), taskTrackerQueryTimeout.duration)
  }

  override def instancesBySpec()(implicit ec: ExecutionContext): Future[InstanceTracker.InstancesBySpec] = {
    def futureCall(): Future[InstanceTracker.InstancesBySpec] =
      (taskTrackerRef ? InstanceTrackerActor.List).mapTo[InstanceTracker.InstancesBySpec].recover {
        case e: AskTimeoutException =>
          throw new TimeoutException(
            "timeout while calling list. If you know what you are doing, you can adjust the timeout " +
              s"with --${config.internalTaskTrackerRequestTimeout.name}."
          )
      }
    tasksByAppTimer.fold(futureCall())(_.timeFuture(futureCall()))
  }

  // TODO(jdef) support pods when counting launched instances
  override def countLaunchedSpecInstancesSync(appId: PathId): Int =
    instancesBySpecSync.specInstances(appId).count(_.isLaunched)
  override def countLaunchedSpecInstancesSync(appId: PathId, filter: Instance => Boolean): Int =
    instancesBySpecSync.specInstances(appId).count { t => t.isLaunched && filter(t) }

  override def countSpecInstancesSync(appId: PathId, filter: Instance => Boolean): Int =
    instancesBySpecSync.specInstances(appId).count(filter)

  override def countSpecInstancesSync(appId: PathId): Int = instancesBySpecSync.specInstances(appId).size
  override def countSpecInstances(appId: PathId)(implicit ec: ExecutionContext): Future[Int] =
    instancesBySpec().map(_.specInstances(appId).size)
  override def hasSpecInstancesSync(appId: PathId): Boolean = instancesBySpecSync.hasSpecInstances(appId)
  override def hasSpecInstances(appId: PathId)(implicit ec: ExecutionContext): Future[Boolean] =
    instancesBySpec().map(_.hasSpecInstances(appId))

  override def specInstancesSync(appId: PathId): Seq[Instance] =
    instancesBySpecSync.specInstances(appId)
  override def specInstances(appId: PathId)(implicit ec: ExecutionContext): Future[Seq[Instance]] =
    instancesBySpec().map(_.specInstances(appId))
  override def specInstancesLaunchedSync(appId: PathId): Seq[Instance] =
    specInstancesSync(appId).filter(_.isLaunched)

  override def instance(taskId: Instance.Id): Future[Option[Instance]] =
    (taskTrackerRef ? InstanceTrackerActor.Get(taskId)).mapTo[Option[Instance]]

  private[this] val tasksByAppTimer =
    metrics.map(metrics => metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "tasksByApp")))

  private[this] implicit val taskTrackerQueryTimeout: Timeout = config.internalTaskTrackerRequestTimeout().milliseconds
}
