package io.ticofab.akkaclusterkubernetes.actor

import java.time.LocalDateTime

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.ClusterEvent.{MemberEvent, MemberExited, MemberUp, UnreachableMember}
import akka.cluster.routing.ClusterRouterPool
import akka.cluster.{Cluster, routing}
import akka.routing.RoundRobinPool
import akka.stream.ActorMaterializer
import io.ticofab.akkaclusterkubernetes.actor.InputSource.Job
import io.ticofab.akkaclusterkubernetes.actor.Router.{EvaluateRate, JobCompleted}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

class Router(scalingController: ActorRef) extends Actor with ActorLogging {

  implicit val ec = context.dispatcher
  implicit val am = ActorMaterializer()(context)

  Cluster(context.system).subscribe(self, classOf[MemberEvent], classOf[UnreachableMember])

  // TODO
  //scalingController ! AddNode

  // the router
  val workersPool = context.actorOf(
    ClusterRouterPool(
      RoundRobinPool(0),
      routing.ClusterRouterPoolSettings(
        totalInstances = 100,
        maxInstancesPerNode = 1,
        allowLocalRoutees = false)
    ).props(Worker()),
    name = "workerRouter")

  // the queue of jobs to run
  val waitingJobs = ListBuffer.empty[Job]
  val jobsArrivedInWindow = ListBuffer.empty[Job]
  val jobsCompletedInWindows = ListBuffer.empty[JobCompleted]

  def submitNextJob(): Unit =
    waitingJobs.headOption.foreach(
      job => {
        workersPool ! job
        waitingJobs -= job
      })

  val evaluationWindow = 10.seconds
  val singleWorkerPower = (Worker.jobsRatePerSecond * evaluationWindow.toSeconds).toInt
  context.system.scheduler.schedule(evaluationWindow, evaluationWindow, self, EvaluateRate)

  var workers: Int = 0
  var lastActionTaken: LocalDateTime = LocalDateTime.now minusSeconds (evaluationWindow.toSeconds * 3)

  override def receive: Receive = {

    case MemberUp(m) =>
      // wait a little and trigger a new job
      log.debug(s"a member joined: ${m.address.toString}")
      workers += 1
      context.system.scheduler.scheduleOnce(1.second, () => submitNextJob())

    // a member left the cluster
    case MemberExited(m) => workers -= 1

    case UnreachableMember(m) => workers -= 1

    case job: Job =>
      // enqueue the new job
      waitingJobs += job
      jobsArrivedInWindow += job

    case job: JobCompleted =>
      // a job has been completed: trigger the next one
      log.debug("job {} completed ({})", job.number, job.completer)
      jobsCompletedInWindows += job
      if (workers > 0) submitNextJob()

    case EvaluateRate =>
      val now = LocalDateTime.now
      val arrivedCompletedDelta = jobsArrivedInWindow.size - jobsCompletedInWindows.size

      val workerPoolPower = singleWorkerPower * workers
      val difference = jobsArrivedInWindow.size - workerPoolPower
      val possibleToTakeAction = now isAfter (lastActionTaken plusSeconds (evaluationWindow.toSeconds * 3))

      log.debug("evaluating rate:")
      log.debug("   waiting jobs:                           {}", waitingJobs.size)
      log.debug("   jobs arrived in window                  {}", jobsArrivedInWindow.size)
      log.debug("   arrivedCompletedDelta                   {}", arrivedCompletedDelta)
      log.debug("   jobs completed in window:               {}", jobsCompletedInWindows.size)
      log.debug("   workers amount:                         {}", workers)
      log.debug("   workers power:                          {}", workerPoolPower)
      log.debug("   arrived vs power difference:            {}", difference)
      log.debug("   possible to take action:                {}", possibleToTakeAction)

      if (possibleToTakeAction) {
        if (difference > 0) {
          log.debug("we need more power, add node")
          lastActionTaken = now
        } else if (difference == 0) {
          if (waitingJobs.size > singleWorkerPower) {
            log.debug("we're burning as much as we receive, but we have a long queue. add worker.")
            lastActionTaken = now
          }
        } else if (difference < 0) {
          if (waitingJobs.size <= singleWorkerPower) {
            if (Math.abs(difference) < singleWorkerPower) {
              log.debug("we have a little more processing power than we need. stay like this.")
            } else {
              log.debug("we have too much power for what we need. remove worker")
              lastActionTaken = now
            }
          } else {
            if (waitingJobs.size > singleWorkerPower * 5) {
              log.debug("we are burning the old queue but not fast enough. add worker.")
              lastActionTaken = now
            } else {
              log.debug("we more power than we need but still burning down old jobs. stay like this.")
            }
          }
        }

      }

      jobsArrivedInWindow.clear()
      jobsCompletedInWindows.clear()

  }
}

object Router {
  def apply(scalingController: ActorRef): Props = Props(new Router(scalingController))

  case class JobCompleted(number: Int, completer: String)

  case object EvaluateRate

}
