---
title: Getting Started
weight: 20
---

Kynetics offers a trial of their <a href="https://www.kynetics.com/android-ota-mdm">Update Factory service</a> which uses the Eclipse hawkBit server.

## From Docker Image

### Overview

| Service / Container | A | B | C |
|---|---|---|---|
| hawkBit Update Server |  &#10003; | &#10003; | &#10003; |
| hawkBit Device Simulator |   |  | &#10003; |
| MySQL |  | &#10003; | &#10003; |
| RabbitMQ |  | &#10003; | &#10003; |

HawkBit Update Server uses username=admin and password=admin as default login credentials. They can be overridden by the environment variables spring.security.user.name and spring.security.user.password which are defined in the corresponding default [application.properties](hawkbit-runtime/hawkbit-update-server/src/main/resources/application.properties).

### A: Run hawkBit Update Server as Docker Container

Start the hawkBit Update Server as a single container

```bash
$ docker run -p 8080:8080 hawkbit/hawkbit-update-server:latest
```

### B: Run hawkBit Update Server with services as Docker Compose

Start the hawkBit Update Server together with an MySQL and RabbitMQ instance as containers

```bash
$ git clone https://github.com/eclipse/hawkbit.git
$ cd hawkbit/hawkbit-runtime/docker
$ docker-compose up -d
```

### C: Run hawkBit Update Server with services as Docker Stack

Start the hawkBit Update Server and Device Simulator together with an MySQL and RabbitMQ instance as services within a swarm

```bash
$ git clone https://github.com/eclipse/hawkbit.git
$ cd hawkbit/hawkbit-runtime/docker
$ docker swarm init
$ docker stack deploy -c docker-compose-stack.yml hawkbit
```

## From Sources

### 1: Clone and build hawkBit
```sh
$ git clone https://github.com/eclipse/hawkbit.git
$ cd hawkbit
$ mvn clean install
```

### 2: Start hawkBit [update server](https://github.com/eclipse/hawkbit/tree/master/hawkbit-runtime/hawkbit-update-server)

```sh
$ java -jar ./hawkbit-runtime/hawkbit-update-server/target/hawkbit-update-server-#version#-SNAPSHOT.jar
```

### 3: Build hawkBit examples

```sh
$ git clone https://github.com/eclipse/hawkbit-examples.git
$ cd hawkbit-examples
$ mvn clean install
```

### 4: Start hawkBit [Device Simulator](https://github.com/eclipse/hawkbit-examples/tree/master/hawkbit-device-simulator)
```sh
$ java -jar ./hawkbit-device-simulator/target/hawkbit-device-simulator-#version#.jar
```

### 5: Generate Getting Started data with [Example Management API Client](https://github.com/eclipse/hawkbit-examples/tree/master/hawkbit-example-mgmt-simulator)

```sh
$ java -jar ./hawkbit-example-mgmt-simulator/target/hawkbit-example-mgmt-simulator-#version#.jar
```
