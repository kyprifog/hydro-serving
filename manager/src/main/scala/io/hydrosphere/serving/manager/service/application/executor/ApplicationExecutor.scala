package io.hydrosphere.serving.manager.service.application.executor

import io.hydrosphere.serving.manager.model.db.Application
import io.hydrosphere.serving.manager.model.{HFResult, HResult, Result}
import io.hydrosphere.serving.manager.service.application._
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.ExecutionContext


trait ApplicationExecutor {
  def executorParams: ExecutorParams

  def execute(predictRequest: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse]
}

object ApplicationExecutor extends Logging {
  def forApplication(
    application: Application,
    params: ExecutorParams
  )(implicit executionContext: ExecutionContext): HResult[ApplicationExecutor] = {
    val plan = application.executionGraph.stages match {
      case stage :: Nil if stage.services.lengthCompare(1) == 0 => // single stage with single service
        Result.ok(
          Executable(
            application = application,
            plan = SimplePlan(stage.key, stage.services.head)
          )
        )
      case _ =>
        Result.ok(
          Executable(
            application = application,
            plan = DAGPlan(application.executionGraph)
          )
        )
    }
    plan.right.map(x => forExecutable(x, params))
  }

  def forExecutable[T <: ApplicationExecutionPlan](
    executable: Executable[T],
    params: ExecutorParams
  )(implicit executionContext: ExecutionContext): ApplicationExecutor = {
    executable.plan match {
      case _: SimplePlan => new StandaloneExecutor(executable.asInstanceOf[Executable[SimplePlan]], params)
      case _: DAGPlan => new DAGExecutor(executable.asInstanceOf[Executable[DAGPlan]], params)
    }
  }
}