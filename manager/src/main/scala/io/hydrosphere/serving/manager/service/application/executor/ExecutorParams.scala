package io.hydrosphere.serving.manager.service.application.executor

import io.hydrosphere.serving.monitoring.monitoring.MonitoringServiceGrpc.MonitoringServiceStub
import io.hydrosphere.serving.profiler.profiler.DataProfilerServiceGrpc.DataProfilerServiceStub
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc.PredictionServiceStub

case class ExecutorParams(
  grpcClient: PredictionServiceStub,
  grpcClientForMonitoring: MonitoringServiceStub,
  grpcClientForProfiler: DataProfilerServiceStub,
  isShadowed: Boolean
)
