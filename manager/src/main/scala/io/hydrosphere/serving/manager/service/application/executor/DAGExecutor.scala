package io.hydrosphere.serving.manager.service.application.executor

import akka.actor.{Actor, ActorLogging, ActorRef}
import io.hydrosphere.serving.manager.model.HFResult
import io.hydrosphere.serving.manager.model.db.{Application, ApplicationStage}
import io.hydrosphere.serving.manager.service.application.RequestTracingInfo
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}

import scala.collection.mutable

/**
  * Responsible for execution of the entire DAG.
  * @param executable
  * @param executorParams
  */
class DAGExecutor(
  val executable: Executable[DAGPlan],
  val executorParams: ExecutorParams
) extends ApplicationExecutor {
  /**
    * Execute the DAG
    * Spawn a DagExecutorActor and return the future.
    *
    * @param predictRequest
    * @param tracingInfo
    * @return
    */
  def execute(predictRequest: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
    // create actor
    // start serving
    // return future
    ???
  }
}

/**
  * Created once per request to the DAG.
  * Handles waiting and merging logic.
  * Should be destroyed after returning final response.
  * @param application
  * @param incomingRequest
  */
class DagExecutorActor(val application: Application, val incomingRequest: PredictRequest) extends Actor with ActorLogging {

  import DagExecutorActor._

  val queues = Map[ApplicationStage, mutable.Queue[PredictResponse]]

  def waiting: Receive = {
    case Start =>
      context become serving(sender())
  }

  def serving(origin: ActorRef): Receive = {
    ???
  }

  override def preStart(): Unit = {
    ??? // initialize queues
    super.preStart()
  }

  override def receive = waiting
}

object DagExecutorActor{
  case class Start()

}