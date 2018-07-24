package io.hydrosphere.serving.manager.service.application

import io.hydrosphere.serving.manager.model._
import io.hydrosphere.serving.manager.model.db._
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import spray.json.{JsObject, JsValue}

import scala.concurrent.Future

trait ApplicationManagementService {
  def serveJsonApplication(jsonServeRequest: JsonServeRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[JsValue]

  def serveGrpcApplication(data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse]

  def allApplications(): Future[Seq[Application]]

  def getApplication(id: Long): HFResult[Application]

  def generateInputsForApplication(appId: Long, signatureName: String): HFResult[JsObject]

  def findVersionUsage(versionId: Long): Future[Seq[Application]]

  def createApplication(req: CreateApplicationRequest): HFResult[Application]

  def updateApplication(req: UpdateApplicationRequest): HFResult[Application]

  def deleteApplication(id: Long): HFResult[Application]
}


