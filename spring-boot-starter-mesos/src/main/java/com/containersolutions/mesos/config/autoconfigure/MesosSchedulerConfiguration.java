package com.containersolutions.mesos.config.autoconfigure;

import com.containersolutions.mesos.scheduler.*;
import com.containersolutions.mesos.scheduler.config.DockerConfigProperties;
import com.containersolutions.mesos.scheduler.config.MesosConfigProperties;
import com.containersolutions.mesos.scheduler.requirements.*;
import com.containersolutions.mesos.scheduler.requirements.ports.PortMerger;
import com.containersolutions.mesos.scheduler.requirements.ports.PortParser;
import com.containersolutions.mesos.scheduler.requirements.ports.PortPicker;
import com.containersolutions.mesos.scheduler.state.StateRepository;
import com.containersolutions.mesos.scheduler.state.StateRepositoryFile;
import com.containersolutions.mesos.scheduler.state.StateRepositoryZookeeper;
import org.apache.mesos.Protos;
import org.apache.mesos.Scheduler;
import org.apache.mesos.state.State;
import org.apache.mesos.state.ZooKeeperState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.function.Supplier;

@Configuration
public class MesosSchedulerConfiguration {
    public static final String MESOS_PORTS = "ports";
    public static final String PORTS_ENV_LABEL = "ports_env";

    @Autowired
    Environment environment;

    @Bean
    public Scheduler scheduler() {
        return new UniversalScheduler();
    }

    @Bean
    public OfferStrategyFilter offerStrategyFilter() {
        return new OfferStrategyFilter();
    }

    private ResourceRequirement simpleScalarRequirement(String name, double minimumRequirement) {
        return (requirement, taskId, offer) -> new OfferEvaluation(
                requirement,
                taskId,
                offer,
                ResourceRequirement.scalarSum(offer, name) > minimumRequirement,
                Protos.Resource.newBuilder()
                        .setType(Protos.Value.Type.SCALAR)
                        .setName(name)
                        .setScalar(Protos.Value.Scalar.newBuilder().setValue(minimumRequirement))
                        .build()
        );

    }

    @Bean
    public AtomicMarkableReference<Protos.FrameworkID> frameworkId() {
        return new AtomicMarkableReference<>(Protos.FrameworkID.newBuilder().setValue("").build(), false);
    }

    @Bean
    @ConditionalOnProperty(prefix = "mesos.state.file", name = "location")
    public StateRepository stateRepositoryFile() {
        return new StateRepositoryFile();
    }

    @Bean
    @ConditionalOnMissingBean(StateRepository.class)
    public StateRepository stateRepositoryZookeeper() {
        return new StateRepositoryZookeeper();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mesos.zookeeper", name = "server")
    public State zkState() {
        return new ZooKeeperState(
                environment.getRequiredProperty("mesos.zookeeper.server"),
                1000,
                TimeUnit.MILLISECONDS,
                "/" + environment.getProperty("mesos.framework.name", "default")
        );
    }

    @Bean
    public Supplier<UUID> uuidSupplier() {
        return UUID::randomUUID;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public MesosConfigProperties mesosConfig() {
        return new MesosConfigProperties();
    }

    @Bean
    @ConditionalOnMissingBean(name = "commandInfoMesosProtoFactory")
    public MesosProtoFactory<Protos.CommandInfo.Builder> commandInfoMesosProtoFactory() {
        return new CommandInfoMesosProtoFactory();
    }

    @Bean
    @ConditionalOnMissingBean(TaskInfoFactory.class)
    @ConditionalOnProperty(prefix = "mesos.docker", name = {"image"})
    public TaskInfoFactory taskInfoFactoryDocker() {
        return new TaskInfoFactoryDocker();
    }

    @Bean
    @ConditionalOnMissingBean(TaskInfoFactory.class)
    @ConditionalOnProperty(prefix = "mesos", name = {"command"})
    public TaskInfoFactory taskInfoFactoryCommand() {
        return new TaskInfoFactoryCommand();
    }

    @Bean
    @ConditionalOnMissingBean(name = "distinctHostRequirement")
    @ConditionalOnProperty(prefix = "mesos.resources", name = "distinctSlave", havingValue = "true")
    @Order(Ordered.LOWEST_PRECEDENCE)
    public ResourceRequirement distinctHostRequirement() {
        return new DistinctSlaveRequirement();
    }

    @Bean
    @ConditionalOnMissingBean(name = "scaleFactorRequirement")
    @ConditionalOnProperty(prefix = "mesos.resources", name = "scale")
    @Order(Ordered.LOWEST_PRECEDENCE)
    public ResourceRequirement scaleFactorRequirement() {
        return new ScaleFactorRequirement(environment.getProperty("mesos.resources.scale", Integer.class, 1));
    }

    @Bean
    @ConditionalOnMissingBean(name = "roleRequirement")
    @ConditionalOnProperty(prefix = "mesos.resources", name = "role", havingValue = "all")
    @Order(Ordered.LOWEST_PRECEDENCE)
    public ResourceRequirement roleRequirement() {
        return new RoleRequirement();
    }


    @Bean
    @ConditionalOnMissingBean(name = "cpuRequirement")
    @ConditionalOnProperty(prefix = "mesos.resources", name = "cpus")
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ResourceRequirement cpuRequirement() {
        return simpleScalarRequirement("cpus", environment.getRequiredProperty("mesos.resources.cpus", Double.class));
    }

    @Bean
    @ConditionalOnMissingBean(name = "memRequirement")
    @ConditionalOnProperty(prefix = "mesos.resources", name = "mem")
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ResourceRequirement memRequirement() {
        return simpleScalarRequirement("mem", environment.getRequiredProperty("mesos.resources.mem", Double.class));
    }

    @Bean
    @ConditionalOnMissingBean(name = "portsRequirement")
    @ConditionalOnProperty(prefix = "mesos.resources", name = "ports")
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ResourceRequirement portsRequirement(PortPicker portPicker) {
        return (requirement, taskId, offer) -> new OfferEvaluation(
                requirement,
                taskId,
                offer,
                portPicker.isValid(PortPicker.toPortSet(offer)),
                portPicker.getResources(PortPicker.toPortSet(offer))
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public PortPicker portPicker(PortParser portParser, PortMerger portMerger) {
        return new PortPicker(portParser, portMerger);
    }

    @Bean
    @ConditionalOnMissingBean
    public PortMerger portMerger(List<PortPicker.PortResourceMapper> portResourceMappers) { // Get a list of all beans of type PortResourceMapper
        return new PortMerger(portResourceMappers);
    }

    @Bean // Not @ConditionalOnMissingBean, because there are multiple beans.
    public PortPicker.PortResourceMapper mesosResourceMapper() {
        // Mesos port resource
        return port -> Protos.Resource.newBuilder()
                        .setType(Protos.Value.Type.RANGES)
                        .setName(MESOS_PORTS)
                        .setRanges(Protos.Value.Ranges.newBuilder().addRange(Protos.Value.Range.newBuilder().setBegin(port.getNumber()).setEnd(port.getNumber())))
                .build();
    }

    @Bean // Not @ConditionalOnMissingBean, because there are multiple beans.
    public PortPicker.PortResourceMapper envVarResourceMapper() {
        // Environment variable resource. Is removed before being sent to mesos. Required so that the task info is able to gather information for injecting ports as env vars.
        return port -> {
            Protos.Value.Set.Builder envText = Protos.Value.Set.newBuilder().addItem(port.getName() + "=" + port.getNumber());
            return Protos.Resource.newBuilder()
                    .setType(Protos.Value.Type.SET)
                    .setName(PORTS_ENV_LABEL)
                    .setSet(envText)
                    .build();
        };
    }

    @Bean
    public TaskMaterializer taskMaterializer() {
        return new TaskMaterializerMinimal();
    }

    @Bean
    @ConditionalOnMissingBean
    public FrameworkInfoFactory frameworkInfoFactory() {
        return new FrameworkInfoFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public CredentialFactory credentialFactory() {
        return new CredentialFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public PortParser portParser(MesosConfigProperties mesosConfig) {
        return new PortParser(mesosConfig);
    }

    @Bean
    @ConditionalOnMissingBean
    public DockerConfigProperties dockerConfigProperties() {
        return new DockerConfigProperties();
    }
}
