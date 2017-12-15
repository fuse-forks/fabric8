package io.fabric8.maven.core.handler;

import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.maven.core.config.ResourceConfig;
import io.fabric8.maven.core.config.VolumeConfig;
import io.fabric8.maven.docker.config.BuildImageConfiguration;
import io.fabric8.maven.docker.config.ImageConfiguration;
import mockit.Mocked;
import org.apache.maven.project.MavenProject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class DeploymentHandlerTest {

    @Mocked
    EnvVarHandler envVarHandler;

    @Mocked
    ProbeHandler probeHandler;

    @Test
    public void deploymentTemplateHandlerTest() {

        MavenProject project = new MavenProject();

        ContainerHandler containerHandler =
                new ContainerHandler(project, envVarHandler, probeHandler);

        PodTemplateHandler podTemplateHandler = new PodTemplateHandler(containerHandler);

        DeploymentHandler deploymentHandler = new DeploymentHandler(podTemplateHandler);

        List<String> mounts = new ArrayList<>();
        List<VolumeConfig> volumes1 = new ArrayList<>();

        //volume config with name and multiple mount
        mounts.add("/path/system");
        mounts.add("/path/sys");

        VolumeConfig volumeConfig1 = new VolumeConfig.Builder().name("test")
                .mounts(mounts).type("hostPath").path("/test/path").build();
        volumes1.clear();
        volumes1.add(volumeConfig1);

        ResourceConfig config = new ResourceConfig.Builder()
                .imagePullPolicy("IfNotPresent")
                .controllerName("testing")
                .withServiceAccount("test-account")
                .withReplicas(5)
                .volumes(volumes1)
                .build();

        List<String> ports = new ArrayList<>();
        ports.add("8080");
        ports.add("9090");

        List<String> tags = new ArrayList<>();
        tags.add("latest");
        tags.add("test");

        //container name with alias
        BuildImageConfiguration buildImageConfiguration = new BuildImageConfiguration.Builder().
                ports(ports).from("fabric8/maven:latest").cleanup("try")
                .tags(tags).compression("gzip").build();

        ImageConfiguration imageConfiguration = new ImageConfiguration.Builder().
                name("test").alias("test-app").buildConfig(buildImageConfiguration)
                .registry("docker.io").build();

        List<ImageConfiguration> images = new ArrayList<>();
        images.add(imageConfiguration);

        Deployment deployment = deploymentHandler.getDeployment(config,images);

        //Assertion
        assertNotNull(deployment.getSpec());
        assertNotNull(deployment.getMetadata());
        assertEquals(5,deployment.getSpec().getReplicas().intValue());
        assertNotNull(deployment.getSpec().getTemplate());
        assertEquals("testing",deployment.getMetadata().getName());
        assertEquals("test-account",deployment.getSpec().getTemplate()
                .getSpec().getServiceAccountName());
        assertFalse(deployment.getSpec().getTemplate().getSpec().getVolumes().isEmpty());
        assertEquals("test",deployment.getSpec().getTemplate().getSpec().
                getVolumes().get(0).getName());
        assertEquals("/test/path",deployment.getSpec().getTemplate()
                .getSpec().getVolumes().get(0).getHostPath().getPath());
        assertNotNull(deployment.getSpec().getTemplate().getSpec().getContainers());

    }

    @Test
    //invalid controller name
    public void deploymentTemplateHandlerSecondTest() {
        try {
            MavenProject project = new MavenProject();

            ContainerHandler containerHandler =
                    new ContainerHandler(project, envVarHandler, probeHandler);

            PodTemplateHandler podTemplateHandler = new PodTemplateHandler(containerHandler);

            DeploymentHandler deploymentHandler = new DeploymentHandler(podTemplateHandler);

            List<String> mounts = new ArrayList<>();
            List<VolumeConfig> volumes1 = new ArrayList<>();

            //volume config with name and multiple mount
            mounts.add("/path/system");
            mounts.add("/path/sys");

            VolumeConfig volumeConfig1 = new VolumeConfig.Builder()
                    .name("test").mounts(mounts).type("hostPath").path("/test/path").build();
            volumes1.clear();
            volumes1.add(volumeConfig1);

            //with invalid controller name
            ResourceConfig config = new ResourceConfig.Builder()
                    .imagePullPolicy("IfNotPresent")
                    .controllerName("TesTing")
                    .withServiceAccount("test-account")
                    .withReplicas(5)
                    .volumes(volumes1)
                    .build();

            List<String> ports = new ArrayList<>();
            ports.add("8080");
            ports.add("9090");

            List<String> tags = new ArrayList<>();
            tags.add("latest");
            tags.add("test");

            //container name with alias
            BuildImageConfiguration buildImageConfiguration = new BuildImageConfiguration.Builder().
                    ports(ports).from("fabric8/maven:latest").cleanup("try")
                    .tags(tags).compression("gzip").build();

            ImageConfiguration imageConfiguration = new ImageConfiguration.Builder().
                    name("test").alias("test-app").buildConfig(buildImageConfiguration)
                    .registry("docker.io").build();

            List<ImageConfiguration> images = new ArrayList<>();
            images.add(imageConfiguration);

            deploymentHandler.getDeployment(config, images);
        }
        //asserting the exact message because
        // it throws the same exception in case controller name is null
        catch(IllegalArgumentException exception) {
            assertEquals("Invalid upper case letter 'T' at index 0 for " +
                    "controller name value: TesTing", exception.getMessage());
        }
    }

    @Test
    //without controller name
    public void deploymentTemplateHandlerThirdTest() {
        try {
            MavenProject project = new MavenProject();

            ContainerHandler containerHandler = new
                    ContainerHandler(project, envVarHandler, probeHandler);

            PodTemplateHandler podTemplateHandler = new PodTemplateHandler(containerHandler);

            DeploymentHandler deploymentHandler = new DeploymentHandler(podTemplateHandler);

            List<String> mounts = new ArrayList<>();
            List<VolumeConfig> volumes1 = new ArrayList<>();

            //volume config with name and multiple mount
            mounts.add("/path/system");
            mounts.add("/path/sys");

            VolumeConfig volumeConfig1 = new VolumeConfig.Builder()
                    .name("test").mounts(mounts).type("hostPath").path("/test/path").build();
            volumes1.clear();
            volumes1.add(volumeConfig1);

            //without controller name
            ResourceConfig config = new ResourceConfig.Builder()
                    .imagePullPolicy("IfNotPresent")
                    .withServiceAccount("test-account")
                    .withReplicas(5)
                    .volumes(volumes1)
                    .build();

            List<String> ports = new ArrayList<>();
            ports.add("8080");
            ports.add("9090");

            List<String> tags = new ArrayList<>();
            tags.add("latest");
            tags.add("test");

            //container name with alias
            BuildImageConfiguration buildImageConfiguration = new BuildImageConfiguration.Builder().
                    ports(ports).from("fabric8/maven:latest").cleanup("try").tags(tags)
                    .compression("gzip").build();

            ImageConfiguration imageConfiguration = new ImageConfiguration.Builder().
                    name("test").alias("test-app").buildConfig(buildImageConfiguration)
                    .registry("docker.io").build();

            List<ImageConfiguration> images = new ArrayList<>();
            images.add(imageConfiguration);

            deploymentHandler.getDeployment(config, images);
        }
        //asserting the exact message because
        //it throws the same exception in case controller name is invalid
        catch(IllegalArgumentException exception) {
            assertEquals("No controller name is specified!", exception.getMessage());
        }
    }
}