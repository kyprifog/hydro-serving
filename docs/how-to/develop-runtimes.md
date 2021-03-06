# Develop runtimes

Sometimes our runtime images are not flexible enough. In that case, you might want to implement one yourself.

The key things you need to know to write your own runtime are:

* How to implement a predefined gRPC service for a dedicated language
* How to our contracts' protobufs work to describe entry points, such as inputs and outputs
* How to create your own Docker image and publish it to an open registry

## Generate GRPC code

There are different approaches to generating client and server gRPC code in [different languages](https://grpc.io/docs/). Let's have a look at how to do that in Python.

First, let's clone our [protos](https://github.com/Hydrospheredata/hydro-serving-protos) repository and prepare a folder for the generated code:

```bash
$ git clone https://github.com/Hydrospheredata/hydro-serving-protos
$ mkdir runtime
```

To generate the gRPC code we need to install additional packages:

```bash
$ pip install grpcio-tools googleapis-common-protos
```

Our custom runtime will require `contracts` and `tf` protobuf messages. Let's generate them:

```bash
$ python -m grpc_tools.protoc --proto_path=./hydro-serving-protos/src/ --python_out=./runtime/ --grpc_python_out=./runtime/ $(find ./hydro-serving-protos/src/hydro_serving_grpc/contract/ -type f -name '*.proto')
$ python -m grpc_tools.protoc --proto_path=./hydro-serving-protos/src/ --python_out=./runtime/ --grpc_python_out=./runtime/ $(find ./hydro-serving-protos/src/hydro_serving_grpc/tf/ -type f -name '*.proto')
$ cd runtime
$ find ./hydro_serving_grpc -type d -exec touch {}/__init__.py \;
```

The structure of the `runtime` should now be as follows:

```bash
runtime
└── hydro_serving_grpc
    ├── __init__.py
    ├── contract
    │   ├── __init__.py
    │   ├── model_contract_pb2.py
    │   ├── model_contract_pb2_grpc.py
    │   ├── model_field_pb2.py
    │   ├── model_field_pb2_grpc.py
    │   ├── model_signature_pb2.py
    │   └── model_signature_pb2_grpc.py
    └── tf
        ├── __init__.py
        ├── api
        │   ├── __init__.py
        │   ├── model_pb2.py
        │   ├── model_pb2_grpc.py
        │   ├── predict_pb2.py
        │   ├── predict_pb2_grpc.py
        │   ├── prediction_service_pb2.py
        │   └── prediction_service_pb2_grpc.py
        ├── tensor_pb2.py
        ├── tensor_pb2_grpc.py
        ├── tensor_shape_pb2.py
        ├── tensor_shape_pb2_grpc.py
        ├── types_pb2.py
        └── types_pb2_grpc.py
```

## Implement Service

Now that we have everything set up, let's implement a runtime. Create a `runtime.py` file and put in the following code:

```python
from hydro_serving_grpc.tf.api.predict_pb2 import PredictRequest, PredictResponse
from hydro_serving_grpc.tf.api.prediction_service_pb2_grpc import PredictionServiceServicer, add_PredictionServiceServicer_to_server
from hydro_serving_grpc.tf.types_pb2 import *
from hydro_serving_grpc.tf.tensor_pb2 import TensorProto
from hydro_serving_grpc.contract.model_contract_pb2 import ModelContract
from concurrent import futures

import os
import time
import grpc
import logging
import importlib


class RuntimeService(PredictionServiceServicer):
    def __init__(self, model_path, contract):
        self.contract = contract
        self.model_path = model_path
        self.logger = logging.getLogger(self.__class__.__name__)

    def Predict(self, request, context):
        self.logger.info(f"Received inference request: {request}")
        
        module = importlib.import_module("func_main")
        executable = getattr(module, self.contract.predict.signature_name)
        result = executable(**request.inputs)

        if not isinstance(result, hs.PredictResponse):
            self.logger.warning(f"Type of a result ({result}) is not `PredictResponse`")
            context.set_code(grpc.StatusCode.OUT_OF_RANGE)
            context.set_details(f"Type of a result ({result}) is not `PredictResponse`")
            return PredictResponse()
        return result


class RuntimeManager:
    def __init__(self, model_path, port):
        self.logger = logging.getLogger(self.__class__.__name__)
        self.port = port
        self.model_path = model_path
        self.server = None
        
        with open(os.path.join(model_path, 'contract.protobin')) as file:
            contract = ModelContract.ParseFromString(file.read())
        self.servicer = RuntimeService(os.path.join(self.model_path, 'files'), contract)

    def start(self):
        self.logger.info(f"Starting PythonRuntime at {self.port}")
        self.server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
        add_PredictionServiceServicer_to_server(self.servicer, self.server)
        self.server.add_insecure_port(f'[::]:{self.port}')
        self.server.start()

    def stop(self, code=0):
        self.logger.info(f"Stopping PythonRuntime at {self.port}")
        self.server.stop(code)
© 2020 GitHub, Inc.
```

Let's quickly review what we have here. `RuntimeManager` simply manages our service, i.e. starts it, stops it, and holds all necessary data. `RuntimeService` is a service that actually implements the`Predict(PredictRequest)` RPC function.

The model will be stored inside the `/model` directory in the Docker container. The structure of `/model` is a follows:

```bash
model
├── contract.protobin
└── files
    ├── ...
    └── ...
```

The`contract.protobin` file will be created by the Manager service. It contains a binary representation of the [ModelContract](https://github.com/Hydrospheredata/hydro-serving-protos/blob/master/src/hydro_serving_grpc/contract/model_contract.proto) message.

`files` directory contains all files of your model.

To run this service let's create an another file `main.py`.

```python
from runtime import RuntimeManager

import os
import time
import logging

logging.basicConfig(level=logging.INFO)

if __name__ == '__main__':
    runtime = RuntimeManager('/model', port=int(os.getenv('APP_PORT', "9090")))
    runtime.start()

    try:
        while True:
            time.sleep(60 * 60 * 24)
    except KeyboardInterrupt:
        runtime.stop()
```

## Publish Runtime

Before we can use the runtime, we have to package it into a container.

To add requirements for installing dependencies, create a `requirements.txt` file and put inside:

```text
grpcio==1.12.1 
googleapis-common-protos==1.5.3
```

Create a Dockerfile to build our image:

```text
FROM python:3.6.5 

ADD . /app
RUN pip install -r /app/requirements.txt

ENV APP_PORT=9090

VOLUME /model 
WORKDIR /app

CMD ["python", "main.py"]
```

`APP_PORT` is an environment variable used by Hydrosphere. When Hydrosphere invokes `Predict` method, it does so via the defined port.

The structure of the `runtime` folder should now look like this:

```bash
runtime
├── Dockerfile
├── hydro_serving_grpc
│   ├── __init__.py
│   ├── contract
│   │   ├── __init__.py
│   │   ├── model_contract_pb2.py
│   │   ├── model_contract_pb2_grpc.py
│   │   ├── model_field_pb2.py
│   │   ├── model_field_pb2_grpc.py
│   │   ├── model_signature_pb2.py
│   │   └── model_signature_pb2_grpc.py
│   └── tf
│       ├── __init__.py
│       ├── api
│       │   ├── __init__.py
│       │   ├── model_pb2.py
│       │   ├── model_pb2_grpc.py
│       │   ├── predict_pb2.py
│       │   ├── predict_pb2_grpc.py
│       │   ├── prediction_service_pb2.py
│       │   └── prediction_service_pb2_grpc.py
│       ├── tensor_pb2.py
│       ├── tensor_pb2_grpc.py
│       ├── tensor_shape_pb2.py
│       ├── tensor_shape_pb2_grpc.py
│       ├── types_pb2.py
│       └── types_pb2_grpc.py
├── main.py
├── requirements.txt
└── runtime.py
```

Build and push the Docker image:

```bash
$ docker build -t {username}/python-runtime-example
$ docker push {username}/python-runtime-example
```

{% hint style="info" %}
Remember that the registry has to be accessible to the Hydrosphere platform so it can pull the runtime whenever it has to run a model with this runtime.
{% endhint %}

That's it. You have just created a simple runtime that you can use in your own projects. It is an almost identical version of our [python runtime implementation](https://github.com/Hydrospheredata/hydro-serving-python). You can always look up details there.

