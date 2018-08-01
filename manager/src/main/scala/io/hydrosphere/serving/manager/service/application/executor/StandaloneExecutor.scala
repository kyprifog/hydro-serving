package io.hydrosphere.serving.manager.service.application.executor

import io.hydrosphere.serving.manager.model.{HFResult, Result}
import io.hydrosphere.serving.manager.service.application.RequestTracingInfo
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}

import scala.concurrent.ExecutionContext

class StandaloneExecutor(
  val executable: Executable[SimplePlan],
  val executorParams: ExecutorParams
)(implicit val executionContext: ExecutionContext) extends ApplicationExecutor {

  override def execute(request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
    request.modelSpec match {
      case Some(servicePath) =>
        val stageId = executable.plan.key.toString
        val modelVersionId = executable.plan.service.modelVersion.id

        val stageInfo = StageInfo(
          modelVersionId = Some(modelVersionId),
          applicationRequestId = tracingInfo.map(_.xRequestId), // TODO get real traceId
          applicationId = executable.application.id,
          signatureName = servicePath.signatureName,
          stageId = stageId,
          applicationNamespace = executable.application.namespace,
          dataProfileFields = executable.plan.service.modelVersion.dataProfileTypes.getOrElse(Map.empty)
        )
        val unit = ExecutionUnit(
          serviceName = stageId,
          servicePath = servicePath.signatureName,
          stageInfo = stageInfo
        )
        CommonExecution.serve(unit, request, executorParams, tracingInfo)

      case None => Result.clientErrorF("ModelSpec in request is not specified")
    }
  }
}
