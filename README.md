# Spring Boot starter for Mesos

[![Join the chat at https://gitter.im/ContainerSolutions/mesos-starter](https://badges.gitter.im/ContainerSolutions/mesos-starter.svg)](https://gitter.im/ContainerSolutions/mesos-starter?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Spring Boot starter package for writing Mesos frameworks

## Features
- Vertical scaling
- Deploy executor on all slaves
- Support for Docker containers

## Getting Started
Start by adding the `spring-boot-starter-mesos` dependency to your project

```
<dependency>
    <groupId>com.github.containersolutions.mesos-starter</groupId>
    <artifactId>spring-boot-starter-mesos</artifactId>
    <version>0.1</version>
</dependency>
```

Make sure your Spring Boot application has a name, by adding the `spring.application.name` to the `application.properties` file i.e.

```
spring.application.name=Sample application
```

To run three instances of a Docker image add the following in `application.properties`

```
mesos.resources.distinctSlave=true
mesos.resources.count=3
mesos.resources.cpus=0.1
mesos.resources.mem=64
mesos.docker.image=tutum/hello-world:latest
```

That is all you need to do if you have annotated your Spring configuration with `@SpringBootApplication` or `@EnableAutoConfiguration`.

```
@SpringBootApplication
public class SampleApplication {

  public static void main(String[] args) {
    SpringApplication.run(SampleApplication.class, args);
  }
}
```

For a complete example, see `mesos-starter-sample` module.

## Starting the application
The only required parameter for the scheduler is `mesos.master`. The value of the parameter is passed directly to the Mesos Scheduler Driver which allows the following formats

- `host:port`
- `zk://host1:port1,host2:port2,.../path`
- `zk://username:password@host1:port1,host2:port2,.../path`
- `file:///path/to/file`

For resiliency it is also recommended to point the application to a Zookeeper cluster and giving the instance a name.
```
mesos.zookeeper.server=zookeeper:2181
mesos.framework.name=sampleApp1
```

The purpose of `mesos.framework.name` is to distinguish instances of the scheduler.

## Running tasks
Currently only Docker and shell commands are supported.

### Docker
Run any Docker image by setting the `mesos.docker.image` property. Eventually by overriding the `CMD` by `mesos.docker.command`

### Shell command
Run a command on by setting the `mesos.shell.command` property.

### Custom task
Extend the `TaskInfoFactory` class to create your own task.

# Offers evaluation
Mesos-starter offers a set of offer evaluation rules
- Physical requirements
- Distinct slave
- Instances count
- Role assigned

They all work in combination with each other, though this might change in the future.

## Physical requirements
Reject offers that does not have the required amount of either CPUs, memory or ports.

### Ports
The ports requirement serves two purposes. Grapping ports and mapping them to the application.
A very base configuration looks like this
```
mesos.resources.ports.http.host=ANY
mesos.command=runwebserver.sh --port=$HTTP
```
This will grap any unprivileged port, and expose it in an environment variable. Value of `.host` can be any off
- `ANY`, `UNPRIVILEGED` will reserve any any port above 1024
- `PRIVILEGED` will only reserve a port below 1024 (included)
- *Any positive number* will only reserve a fixed port.

When running with containers you can add the `.container` to map it to a container port when running in bridge mode, i.e.

```
mesos.resources.ports.http.host=ANY
mesos.resources.ports.http.container=80
mesos.docker.network=BRIDGE
mesos.docker.image=tutum/hello-world
```

This will reserve any port above 1024 and let docker map it to port 80 on the container.

## Distinct slave
This rule will make sure that offers for hosts where the application is already running are being rejected.

## Instances count
This rule will make sure that only a certain number of instances are running in the Mesos cluster. The instances count is exposed as a managed bean that can be accessed through Actuator Management API. Furthermore it'll also be possible to insert your own instances count bean.
It is recommended to have this rule enabled in most cases.

## Role assigned
This rule only accept offers assigned to the Role defined in `mesos.role`.

## Framework Authentication/Authorisation
To use [Framework Authentication](http://mesos.apache.org/documentation/latest/authentication/), please pass the following settings:

| Command | Description | Default | Required |
| --- | --- | --- | --- |
| mesos.principal | The Mesos principal | | |
| mesos.secret | The Mesos secret | | |

# Use cases

A few good examples

## Stateless web application
For a stateless web application that can run anywhere in the cluster with only a requirement for a single network port, the following should be sufficient

```
mesos.resources.count=3
mesos.resources.cpus=0.1
mesos.resources.mem=64
mesos.resources.ports=1
```

This will run 3 instances of the application with one port exposed. Bare in mind that they all might run on the very same host.

## Distributed database application
For a distributed database you want to run a certain number of instances and never more than one on every host. To achieve that you can enable `count` and `distinctSlave`, like

```
mesos.resources.count=3
mesos.resources.distinctSlave=true
mesos.resources.cpus=0.1
mesos.resources.mem=64
mesos.resources.ports=1
```

## Cluster wide system daemon
Often operations would like to run a single application on each host in the cluster to harvest information from every single node. This can be achieved by not adding the instances count rule and adding the Distinct slave rule.

```
mesos.resources.distinctSlave=true
mesos.resources.cpus=0.1
mesos.resources.mem=64
```

Another, safer, way to achieve the same result is by assigning resources to a specific role on all nodes. I.e. by adding the following to `/etc/mesos-slave/resources`
```
cpus(sampleDaemon):0.2; mem(sampleDaemon):64; ports(sampleDaemon):[514-514];
```

And configure the scheduler with the following options

```
mesos.role=sampleDaemon
mesos.resources.distinctSlave=true
mesos.resources.role=all
```

This way the scheduler will take all resources allocated to the role and make sure it's only running once in every single slave.

It is always recommended to run the scheduler with a Mesos role and reserved resources in such cases to make sure that scheduler is being offered resources for all nodes in the cluster.

### Framework shutdown
The scheduler can survive `SIGKILL` or being lost (system crashes etc.). If you want to completely de-register the framework and shutdown all tasks, just stop the scheduler with a plain `SIGTERM`.
