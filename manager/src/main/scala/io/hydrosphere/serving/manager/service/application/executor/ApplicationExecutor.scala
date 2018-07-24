package io.hydrosphere.serving.manager.service.application.executor

import java.util.concurrent.atomic.AtomicReference

import io.hydrosphere.serving.grpc.{AuthorityReplacerInterceptor, Header, Headers}
import io.hydrosphere.serving.manager.model.Result.ClientError
import io.hydrosphere.serving.manager.model.db.Application
import io.hydrosphere.serving.manager.model.{HFResult, HResult, Result}
import io.hydrosphere.serving.manager.service.application._
import io.hydrosphere.serving.manager.service.clouddriver.CloudDriverService
import io.hydrosphere.serving.manager.util.TensorUtil
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.monitoring.{ExecutionError, ExecutionInformation, ExecutionMetadata}
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.tensor.TensorProto
import io.hydrosphere.serving.tensorflow.tensor_shape.TensorShapeProto
import io.hydrosphere.serving.tensorflow.types.DataType
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.Future
import scala.util.{Failure, Try}


trait ApplicationExecutor {
  def executorParams: ExecutorParams

  def execute(predictRequest: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse]

  protected def serve(unit: ExecutionUnit, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
    val verificationResults = request.inputs.map {
      case (name, tensor) => name -> TensorUtil.verifyShape(tensor)
    }

    val errors = verificationResults.filter {
      case (_, t) => t.isLeft
    }.mapValues(_.left.get)

    if (errors.isEmpty) {
      val modelVersionIdHeaderValue = new AtomicReference[String](null)
      val latencyHeaderValue = new AtomicReference[String](null)

      var requestBuilder = executorParams.grpcClient
        .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, unit.serviceName)
        .withOption(Headers.XServingModelVersionId.callOptionsClientResponseWrapperKey, modelVersionIdHeaderValue)
        .withOption(Headers.XEnvoyUpstreamServiceTime.callOptionsClientResponseWrapperKey, latencyHeaderValue)

      if (tracingInfo.isDefined) {
        val tr = tracingInfo.get
        requestBuilder = requestBuilder
          .withOption(Headers.XRequestId.callOptionsKey, tr.xRequestId)

        if (tr.xB3requestId.isDefined) {
          requestBuilder = requestBuilder
            .withOption(Headers.XB3TraceId.callOptionsKey, tr.xB3requestId.get)
        }

        if (tr.xB3SpanId.isDefined) {
          requestBuilder = requestBuilder
            .withOption(Headers.XB3ParentSpanId.callOptionsKey, tr.xB3SpanId.get)
        }
      }

      val verifiedInputs = verificationResults.mapValues(_.right.get)
      val verifiedRequest = request.copy(inputs = verifiedInputs)

      requestBuilder
        .predict(verifiedRequest)
        .transform(
          response => {
            val latency = getLatency(latencyHeaderValue)
            val res = if (latency.isSuccess) {
              response.addInternalInfo(
                "system.latency" -> latency.get
              )
            } else {
              response
            }

            sendToDebug(ResponseOrError.Response(res), verifiedRequest, getCurrentExecutionUnit(unit, modelVersionIdHeaderValue))
            Result.ok(response)
          },
          thr => {
            logger.error("Can't send message to GATEWAY_KAFKA", thr)
            sendToDebug(ResponseOrError.Error(ExecutionError(thr.toString)), verifiedRequest, getCurrentExecutionUnit(unit, modelVersionIdHeaderValue))
            thr
          }
        )
    } else {
      Future.successful(
        Result.errors(
          errors.map {
            case (name, err) =>
              ClientError(s"Shape verification error for input $name: $err")
          }.toSeq
        )
      )
    }
  }

  //TODO REMOVE!
  protected def sendToDebug(responseOrError: ResponseOrError, predictRequest: PredictRequest, executionUnit: ExecutionUnit): Unit = {
    if (executorParams.isShadowed) {
      val execInfo = ExecutionInformation(
        metadata = Option(ExecutionMetadata(
          applicationId = executionUnit.stageInfo.applicationId,
          stageId = executionUnit.stageInfo.stageId,
          modelVersionId = executionUnit.stageInfo.modelVersionId.getOrElse(-1),
          signatureName = executionUnit.stageInfo.signatureName,
          applicationRequestId = executionUnit.stageInfo.applicationRequestId.getOrElse(""),
          requestId = executionUnit.stageInfo.applicationRequestId.getOrElse(""), //todo fetch from response,
          applicationNamespace = executionUnit.stageInfo.applicationNamespace.getOrElse(""),
          dataTypes = executionUnit.stageInfo.dataProfileFields
        )),
        request = Option(predictRequest),
        responseOrError = responseOrError
      )

      executorParams.grpcClientForMonitoring
        .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, CloudDriverService.MONITORING_NAME)
        .analyze(execInfo)
        .onComplete {
          case Failure(thr) =>
            logger.warn("Can't send message to the monitoring service", thr)
          case _ =>
            Unit
        }

      executorParams.grpcClientForProfiler
        .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, CloudDriverService.PROFILER_NAME)
        .analyze(execInfo)
        .onComplete {
          case Failure(thr) =>
            logger.warn("Can't send message to the data profiler service", thr)
          case _ => Unit
        }
    }
  }

  private def getHeaderValue(header: Header): Option[String] = Option(header.contextKey.get())

  private def getCurrentExecutionUnit(unit: ExecutionUnit, modelVersionIdHeaderValue: AtomicReference[String]): ExecutionUnit = Try{
    Option(modelVersionIdHeaderValue.get()).map(_.toLong)
  }.map(s => unit.copy(stageInfo = unit.stageInfo.copy(modelVersionId = s)))
    .getOrElse(unit)

  private def getLatency(latencyHeaderValue: AtomicReference[String]): Try[TensorProto] = {
    Try({
      Option(latencyHeaderValue.get()).map(_.toLong)
    }).map(v => TensorProto(
      dtype = DataType.DT_INT64,
      int64Val = Seq(v.getOrElse(0)),
      tensorShape = Some(TensorShapeProto(dim = Seq(TensorShapeProto.Dim(1))))
    ))
  }
}

object ApplicationExecutor extends Logging {
  def forApplication(application: Application, params: ExecutorParams): HResult[ApplicationExecutor] = {
    val plan = application.executionGraph.stages match {
      case stage :: Nil if stage.services.lengthCompare(1) == 0 => // single stage with single service
        Result.ok(
          Executable(
            application = application,
            plan = SimplePlan(stage.key ,stage.services.head)
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

  def forExecutable[T <: ApplicationExecutionPlan](executable: Executable[T], params: ExecutorParams) = {
    executable.plan match {
      case _: SimplePlan => new StandaloneExecutor(executable.asInstanceOf[Executable[SimplePlan]], params)
      case _: DAGPlan => new DAGExecutor(executable.asInstanceOf[Executable[DAGPlan]], params)
    }
  }
}
