---
description: 'Estimated completion time: 11m.'
---

# Using Deployment Configurations

This tutorial provides a walkthrough of how user can configure deployed Applications.

 Step by step we will : 

1. Train and upload an example [model version](../overview/concepts.md#models-and-model-versions)
2. Create a [Deployment Configuration](../overview/concepts.md#deployment-configurations)
3. Create an [Application](../overview/concepts.md#applications) from uploaded model version with previously created deployment configuration
4. Examine settings of Kubernetes cluster

## Prerequisites

{% hint style="warning" %}
This tutorial is relevant only for k8s installed Hydrosphere. Please refer to [How to Install Hydrosphere on Kubernetes cluster](../installation/#kubernetes-installation)
{% endhint %}

* [Hydrosphere platform installed in Kubernetes cluster](../installation/#kubernetes-installation)
* [Python SDK](../installation/sdk.md#installation)

## Upload Model

In this section we describe resources required to create and upload an example model used in further sections. If you have no prior experience with uploading models to Hydrosphere platform we suggest you visit the [Getting Started Tutorial](../getting-started.md).

Here are the resources used to train `sklearn.ensemble.GradientBoostingClassifier` and upload it to Hydrosphere cluster.

{% tabs %}
{% tab title="requirements.txt" %}
`requirements.txt` is a list of Python dependencies used during the process of building model image.

```text
numpy~=1.18
scipy==1.4.1
scikit-learn~=0.23
```
{% endtab %}

{% tab title="serving.yaml" %}
`serving.yaml` is a[ resource definition](../overview/concepts.md#resource-definitions) that describes how model should be built and uploaded to Hydrosphere platform.

{% code title="serving.yaml" %}
```yaml
kind: Model
name: my-model
runtime: hydrosphere/serving-runtime-python-3.7:2.3.2
install-command: pip install -r requirements.txt
payload:
  - src/
  - requirements.txt
  - model.joblib
contract:
  name: infer
  inputs:
    x:
      shape: [30]
      type: double
  outputs:
    y:
      shape: scalar
      type: int64
```
{% endcode %}
{% endtab %}

{% tab title="train.py" %}
`train.py` is used to generate a `model.joblib` which is loaded from `func_main.py` during model serving.

Run `python train.py` to generate `model.joblib`

{% code title="train.py" %}
```python
import joblib
import pandas as pd
from sklearn.datasets import make_blobs
from sklearn.ensemble import GradientBoostingClassifier

# initialize data
X, y = make_blobs(n_samples=3000, n_features=30)

# create a model
model = GradientBoostingClassifier(n_estimators=200)
model.fit(X, y)

# Save training data and model
pd.DataFrame(X).to_csv("training_data.csv", index=False)
joblib.dump(model, "model.joblib")
```
{% endcode %}
{% endtab %}

{% tab title="func\_main.py" %}
`func_main.py` is a script which serves requests and produces responses. 

{% code title="func\_main.py" %}
```python
import joblib
import numpy as np

# Load model once
model = joblib.load("/model/files/model.joblib")


def infer(x):
    # Make a prediction
    y = model.predict(x[np.newaxis])

    # Return the scalar representation of y
    return {"y": np.asscalar(y)}
```
{% endcode %}
{% endtab %}
{% endtabs %}

Our folder structure should look like this.

{% hint style="info" %}
Do not forget to run `python train.py` to generate `model.joblib`!
{% endhint %}

```yaml
dep_config_tutorial
├── model.joblib
├── train.py
├── requirements.txt
├── serving.yaml
└── src
    └── func_main.py
```

After we have made sure that all files are placed correctly, we can upload the model to the Hydrosphere platform by running `hs upload` from command line.

```bash
hs upload
```

## Create Deployment Configuration

Next, we are going to create and upload an instance of [Deployment Configuration](../overview/concepts.md#deployment-configurations) to Hydrosphere platform.

Deployment Configurations describe with which Kubernetes settings Hydrosphere should deploy [servables](../overview/concepts.md#servable). You can specify Pod [Affinity](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.19/#affinity-v1-core) and [Tolerations](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.19/#toleration-v1-core), number of desired pods in deployment, [ResourceRequirements](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.19/#resourcerequirements-v1-core) for the model container, and [HorizontalPodAutoScaler](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.19/#horizontalpodautoscalerspec-v1-autoscaling) settings.

Created Deployment Configurations can be attached to Servables and Model Variants inside of Application. 

Deployment Configurations are immutable and cannot be changed after they've been uploaded to the Hydrosphere platform. 

You can create and upload Deployment Configuration to Hydrosphere via [YAML Resource definition](../how-to/write-definitions.md#kind-deploymentconfiguration) or via [Python SDK](../installation/sdk.md).

For this tutorial we'll create a deployment configuration with 4 initial pods per deployment and HPA.

{% tabs %}
{% tab title="YAML Resource Definition" %}
Create the deployment configuration resource definition:

{% code title="deployment\_configuration.yaml" %}
```yaml
kind: DeploymentConfiguration
name: my-dep-config
deployment:
  replicaCount: 2
hpa:
  minReplicas: 2
  maxReplicas: 4
  cpuUtilization: 70
```
{% endcode %}

And upload it to the Hydrosphere platform via

```bash
hs apply -f deployment_configuration.yaml
```
{% endtab %}

{% tab title="Python SDK" %}
```python
from hydrosdk import Cluster, DeploymentConfigurationBuilder

cluster = Cluster("http://localhost")

dep_config_builder = DeploymentConfigurationBuilder("my-dep-config", cluster)
dep_config = dep_config_builder. \
    with_replicas(replica_count=2). \
    with_hpa(max_replicas=4,
             min_replicas=2,
             target_cpu_utilization_percentage=70).build()
```
{% endtab %}
{% endtabs %}

## Create an Application

{% tabs %}
{% tab title="YAML Resource Definition" %}
Create the application resource definition

{% code title="application.yaml" %}
```yaml
kind: Application
name: my-app-with-config
pipeline:
  - - model: my-model:1
      weight: 100
      deploymentConfiguartion: my-config
```
{% endcode %}

And upload it to the Hydrosphere platform via

```bash
hs apply -f application.yaml
```
{% endtab %}

{% tab title="Python SDK" %}
```python
from application import ApplicationBuilder, ExecutionStageBuilder
from hydrosdk import ModelVersion, Cluster, DeploymentConfiguration

cluster = Cluster('http:\\localhost')
my_model = ModelVersion.find(cluster, "my-model", 1)
my_config = DeploymentConfiguration.find(cluster, "my-config")

stage = ExecutionStageBuilder().with_model_variant(model_version=my_model,
                                                   weight=100,
                                                   deployment_configuration=my_config).build()
                                                   
app = ApplicationBuilder(cluster, "my-app-with-config").with_stage(stage).build()
```
{% endtab %}
{% endtabs %}

## Invoke this application

To check how our deployment config works we'll send a series of requests to our deployed application via Python SDK

```python
import numpy as np
import pandas as pd
from hydrosdk import Cluster, Application

cluster = Cluster("http://localhost", grpc_address="localhost:9090")

app = Application.find(cluster, "my-app-with-config")
predictor = app.predictor()

X = pd.read_csv("training_data.csv")


for sample in X[:1000].itertuples(index=False):
    x = np.array(sample)
    y = predictor.predict({"x": x})

```
