/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.runtime.container.karaf;

import io.fabric8.api.GeoLocationService;
import io.fabric8.api.PortService;
import io.fabric8.common.util.Strings;
import io.fabric8.internal.ImmutableContainerBuilder;
import io.fabric8.zookeeper.bootstrap.BootstrapConfiguration;
import io.fabric8.zookeeper.utils.ZooKeeperUtils;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

import io.fabric8.api.Container;
import io.fabric8.api.ContainerRegistration;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.ZkDefs;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.utils.HostUtils;
import io.fabric8.utils.Ports;
import io.fabric8.api.SystemProperties;
import io.fabric8.zookeeper.ZkPath;

import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationEvent;
import org.osgi.service.cm.ConfigurationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.List;
import java.util.Set;

import static io.fabric8.zookeeper.ZkPath.CONFIG_CONTAINER;
import static io.fabric8.zookeeper.ZkPath.CONFIG_VERSIONS_CONTAINER;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_ADDRESS;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_ALIVE;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_BINDADDRESS;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_DOMAINS;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_GEOLOCATION;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_HTTP;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_IP;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_JMX;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_LOCAL_HOSTNAME;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_LOCAL_IP;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_MANAGED;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_PUBLIC_IP;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_PORT_MAX;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_PORT_MIN;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_RESOLVER;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_SSH;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.create;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.createDefault;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.deleteIfExists;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.deleteSafe;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.exists;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.getStringData;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.getSubstitutedData;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.setData;

@ThreadSafe
@Component(name = "io.fabric8.container.registration.karaf", label = "Fabric8 Karaf Container Registration", metatype = false)
@Service({ ContainerRegistration.class, ConfigurationListener.class, ConnectionStateListener.class })
public final class KarafContainerRegistration extends AbstractComponent implements ContainerRegistration, ConfigurationListener, ConnectionStateListener {

    private transient Logger LOGGER = LoggerFactory.getLogger(KarafContainerRegistration.class);

    private static final String MANAGEMENT_PID = "org.apache.karaf.management";
    private static final String SSH_PID = "org.apache.karaf.shell";
    private static final String HTTP_PID = "org.ops4j.pax.web";
    private static final String AGENT_PID = "io.fabric8.agent";

    private static final String JMX_SERVICE_URL = "serviceUrl";
    private static final String RMI_REGISTRY_BINDING_PORT_KEY = "rmiRegistryPort";
    private static final String RMI_SERVER_BINDING_PORT_KEY = "rmiServerPort";
    private static final String SSH_BINDING_PORT_KEY = "sshPort";
    private static final String HTTP_BINDING_PORT_KEY = "org.osgi.service.http.port";
    private static final String HTTPS_BINDING_PORT_KEY = "org.osgi.service.http.port.secure";
    private static final String AGENT_DISABLED = "disabled";

    private static final String RMI_REGISTRY_CONNECTION_PORT_KEY = "rmiRegistryConnectionPort";
    private static final String RMI_SERVER_CONNECTION_PORT_KEY = "rmiServerConnectionPort";
    private static final String SSH_CONNECTION_PORT_KEY = "sshConnectionPort";
    private static final String HTTP_CONNECTION_PORT_KEY = "org.osgi.service.http.connection.port";
    private static final String HTTPS_CONNECTION_PORT_KEY = "org.osgi.service.http.connection.port.secure";

    private static final String HTTP_ENABLED = "org.osgi.service.http.enabled";
    private static final String HTTPS_ENABLED = "org.osgi.service.http.secure.enabled";

    @Reference(referenceInterface = ConfigurationAdmin.class)
    private final ValidatingReference<ConfigurationAdmin> configAdmin = new ValidatingReference<ConfigurationAdmin>();
    @Reference(referenceInterface = RuntimeProperties.class)
    private final ValidatingReference<RuntimeProperties> runtimeProperties = new ValidatingReference<RuntimeProperties>();
    @Reference(referenceInterface = CuratorFramework.class)
    private final ValidatingReference<CuratorFramework> curator = new ValidatingReference<CuratorFramework>();
    @Reference(referenceInterface = PortService.class)
    private final ValidatingReference<PortService> portService = new ValidatingReference<PortService>();
    @Reference(referenceInterface = GeoLocationService.class)
    private final ValidatingReference<GeoLocationService> geoLocationService = new ValidatingReference<GeoLocationService>();
    @Reference(referenceInterface = BootstrapConfiguration.class)
    private final ValidatingReference<BootstrapConfiguration> bootstrapConfiguration = new ValidatingReference<BootstrapConfiguration>();

    private String runtimeIdentity;
    private String ip;

    @Activate
    void activate() {
        activateInternal();
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
    }

    private void activateInternal() {
        RuntimeProperties sysprops = runtimeProperties.get();
        runtimeIdentity = sysprops.getRuntimeIdentity();
        String version = sysprops.getProperty("fabric.version", ZkDefs.DEFAULT_VERSION);
        String profiles = sysprops.getProperty("fabric.profiles");
        try {
            if (profiles != null) {
                String versionNode = CONFIG_CONTAINER.getPath(runtimeIdentity);
                String profileNode = CONFIG_VERSIONS_CONTAINER.getPath(version, runtimeIdentity);
                createDefault(curator.get(), versionNode, version);
                createDefault(curator.get(), profileNode, profiles);
            }

            checkAlive();

            String domainsNode = CONTAINER_DOMAINS.getPath(runtimeIdentity);
            Stat stat = exists(curator.get(), domainsNode);
            if (stat != null) {
                deleteSafe(curator.get(), domainsNode);
            }

            boolean openshiftEnv = Strings.notEmpty(System.getenv("OPENSHIFT_FUSE_DIR"));

            ZooKeeperUtils.createDefault(curator.get(), CONTAINER_BINDADDRESS.getPath(runtimeIdentity), bootstrapConfiguration.get().getBindAddress());
            ZooKeeperUtils.createDefault(curator.get(), CONTAINER_RESOLVER.getPath(runtimeIdentity), getContainerResolutionPolicy(curator.get(), runtimeIdentity));
            setData(curator.get(), CONTAINER_LOCAL_HOSTNAME.getPath(runtimeIdentity), HostUtils.getLocalHostName());
            if (openshiftEnv) {
                setData(curator.get(), CONTAINER_LOCAL_IP.getPath(runtimeIdentity), System.getenv("OPENSHIFT_FUSE_IP"));
                setData(curator.get(), CONTAINER_PUBLIC_IP.getPath(runtimeIdentity), HostUtils.getLocalIp());
            } else {
                setData(curator.get(), CONTAINER_LOCAL_IP.getPath(runtimeIdentity), HostUtils.getLocalIp());
            }
            //Check if there are addresses specified as system properties and use them if there is not an existing value in the registry.
            //Mostly usable for adding values when creating containers without an existing ensemble.
            for (String resolver : ZkDefs.VALID_RESOLVERS) {
                String address = (String) bootstrapConfiguration.get().getConfiguration().get(resolver);
                if (address != null && !address.isEmpty() && exists(curator.get(), CONTAINER_ADDRESS.getPath(runtimeIdentity, resolver)) == null) {
                    setData(curator.get(), CONTAINER_ADDRESS.getPath(runtimeIdentity, resolver), address);
                }
            }

            ip =  getSubstitutedData(curator.get(), getContainerPointer(curator.get(), runtimeIdentity));
            setData(curator.get(), CONTAINER_IP.getPath(runtimeIdentity), ip);
            if (Boolean.parseBoolean(runtimeProperties.get().getProperty("service.geoip.enabled", "false"))) {
                createDefault(curator.get(), CONTAINER_GEOLOCATION.getPath(runtimeIdentity), geoLocationService.get().getGeoLocation());
            }

            //We are creating a dummy container object, since this might be called before the actual container is ready.
            Container current = new ImmutableContainerBuilder().id(runtimeIdentity).ip(ip).build();

            if(System.getProperty(SystemProperties.JAVA_RMI_SERVER_HOSTNAME) == null){
                System.setProperty(SystemProperties.JAVA_RMI_SERVER_HOSTNAME, current.getIp());
            }

            Configuration config = configAdmin.get().getConfiguration(AGENT_PID, null);
            String disabled = "false";
            if (config != null && config.getProperties() != null) {
                disabled = (String) config.getProperties().get(AGENT_DISABLED);
            }
            boolean managed = !"true".equalsIgnoreCase(disabled);
            setData(curator.get(), CONTAINER_MANAGED.getPath(runtimeIdentity), Boolean.toString(managed));

            registerJmx(current);
            registerSsh(current);
            registerHttp(current);

            //Set the port range values
            String minimumPort = sysprops.getProperty(ZkDefs.MINIMUM_PORT);
            if(minimumPort == null){
                String minPort = (String) bootstrapConfiguration.get().getConfiguration().get("minimum.port");
                minimumPort = minPort;
            }
            String maximumPort = sysprops.getProperty(ZkDefs.MAXIMUM_PORT);
            if(maximumPort == null){
                String maxPort = (String) bootstrapConfiguration.get().getConfiguration().get("maximum.port");
                maximumPort = maxPort;
            }
            createDefault(curator.get(), CONTAINER_PORT_MIN.getPath(runtimeIdentity), minimumPort);
            createDefault(curator.get(), CONTAINER_PORT_MAX.getPath(runtimeIdentity), maximumPort);
        } catch (Exception e) {
            LOGGER.warn("Error updating Fabric Container information. This exception will be ignored.", e);
        }
    }

    @Override
    public void stateChanged(CuratorFramework client, ConnectionState newState) {
        if (isValid()) {
            switch (newState) {
            case CONNECTED:
            case RECONNECTED:
                try {
                    checkAlive();
                } catch (Exception ex) {
                    LOGGER.error("Error while checking/setting container status.");
                }
                break;
            }
        }
    }

    private void checkAlive() throws Exception {
        RuntimeProperties sysprops = runtimeProperties.get();
        String runtimeIdentity = sysprops.getRuntimeIdentity();
        String nodeAlive = CONTAINER_ALIVE.getPath(runtimeIdentity);
        Stat stat = exists(curator.get(), nodeAlive);
        if (stat != null) {
            if (stat.getEphemeralOwner() != curator.get().getZookeeperClient().getZooKeeper().getSessionId()) {
                // should not prevent creation if delete fails!
                deleteIfExists(curator.get(), nodeAlive);
                create(curator.get(), nodeAlive, CreateMode.EPHEMERAL);
            }
        } else {
            create(curator.get(), nodeAlive, CreateMode.EPHEMERAL);
        }
    }

    private void registerJmx(Container container) throws Exception {
        // first atomic scope = finding rmiRegistryPort
        PortService.Lock lock = null;
        int rmiRegistryPort, rmiRegistryConnectionPort;
        int rmiServerPort, rmiServerConenctionPort;
        try {
            lock = portService.get().acquirePortLock();
            rmiRegistryPort = getRmiRegistryPort(container, lock);
            rmiRegistryConnectionPort = getRmiRegistryConnectionPort(container, rmiRegistryPort);
            portService.get().registerPort(container, MANAGEMENT_PID, RMI_REGISTRY_BINDING_PORT_KEY, rmiRegistryPort, lock);
        } finally {
            portService.get().releasePortLock(lock);
        }
        try {
            lock = portService.get().acquirePortLock();
            rmiServerPort = getRmiServerPort(container, lock);
            rmiServerConenctionPort = getRmiServerConnectionPort(container, rmiServerPort);
            portService.get().registerPort(container, MANAGEMENT_PID, RMI_SERVER_BINDING_PORT_KEY, rmiServerPort, lock);
        } finally {
            portService.get().releasePortLock(lock);
        }
        // second atomic scope = finding rmiServerPort
        String jmxUrl = getJmxUrl(container.getId(), rmiServerConenctionPort, rmiRegistryConnectionPort);
        setData(curator.get(), CONTAINER_JMX.getPath(container.getId()), jmxUrl);
        Configuration configuration = configAdmin.get().getConfiguration(MANAGEMENT_PID, null);
        Dictionary<String, Object> dictionary = configuration == null ? null : configuration.getProperties();
        String rmiServerHost = "${rmiServerHost}";
        String rmiRegistryHost = "${rmiRegistryHost}";

        if (configuration != null) {
            rmiServerHost = (String) dictionary.get("rmiServerHost");
            rmiRegistryHost = (String) dictionary.get("rmiRegistryHost");
        }
        String serviceUrl = String.format("service:jmx:rmi://%s:%d/jndi/rmi://%s:%d/karaf-%s",
                rmiServerHost, rmiServerPort, rmiRegistryHost, rmiRegistryPort, runtimeIdentity);

        boolean changed = updateIfNeeded(dictionary, RMI_REGISTRY_BINDING_PORT_KEY, rmiRegistryPort);
        changed |= updateIfNeeded(dictionary, RMI_SERVER_BINDING_PORT_KEY, rmiServerPort);
        changed |= updateIfNeeded(dictionary, JMX_SERVICE_URL, serviceUrl);
        if (configuration != null && changed) {
            configuration.update(dictionary);
        }
    }

    private int getRmiRegistryPort(Container container, PortService.Lock lock) throws IOException, KeeperException, InterruptedException {
        return getOrAllocatePortForKey(container, MANAGEMENT_PID, RMI_REGISTRY_BINDING_PORT_KEY, Ports.DEFAULT_RMI_REGISTRY_PORT, lock);
    }

    private int getRmiRegistryConnectionPort(Container container, int defaultValue) throws IOException, KeeperException, InterruptedException {
        return getPortForKey(container, MANAGEMENT_PID, RMI_REGISTRY_CONNECTION_PORT_KEY, defaultValue);
    }

    private int getRmiServerPort(Container container, PortService.Lock lock) throws IOException, KeeperException, InterruptedException {
        return getOrAllocatePortForKey(container, MANAGEMENT_PID, RMI_SERVER_BINDING_PORT_KEY, Ports.DEFAULT_RMI_SERVER_PORT, lock);
    }

    private int getRmiServerConnectionPort(Container container, int defaultValue) throws IOException, KeeperException, InterruptedException {
        return getPortForKey(container, MANAGEMENT_PID, RMI_SERVER_CONNECTION_PORT_KEY, defaultValue);
    }

    private String getJmxUrl(String name, int serverConnectionPort, int registryConnectionPort) throws IOException, KeeperException, InterruptedException {
        return "service:jmx:rmi://${zk:" + name + "/ip}:" + serverConnectionPort + "/jndi/rmi://${zk:" + name + "/ip}:" + registryConnectionPort + "/karaf-" + name;
    }

    private void registerSsh(Container container) throws Exception {
        PortService.Lock lock = null;
        int sshPort, sshConnectionPort;
        try {
            lock = portService.get().acquirePortLock();
            sshPort = getSshPort(container, lock);
            sshConnectionPort = getSshConnectionPort(container, sshPort);
            portService.get().registerPort(container, SSH_PID, SSH_BINDING_PORT_KEY, sshPort, lock);
        } finally {
            portService.get().releasePortLock(lock);
        }

        String sshUrl = getSshUrl(container.getId(), sshConnectionPort);
        setData(curator.get(), CONTAINER_SSH.getPath(container.getId()), sshUrl);
        Configuration configuration = configAdmin.get().getConfiguration(SSH_PID, null);
        if (configuration != null) {
            Dictionary<String, Object> dictionary = configuration.getProperties();
            updateIfNeeded(dictionary, SSH_BINDING_PORT_KEY, sshPort);
            configuration.update(dictionary);
        }
    }

    private int getSshPort(Container container, PortService.Lock lock) throws IOException, KeeperException, InterruptedException {
        return getOrAllocatePortForKey(container, SSH_PID, SSH_BINDING_PORT_KEY, Ports.DEFAULT_KARAF_SSH_PORT, lock);
    }

    private int getSshConnectionPort(Container container, int defaultValue) throws IOException, KeeperException, InterruptedException {
        return getPortForKey(container, SSH_PID, SSH_CONNECTION_PORT_KEY, defaultValue);
    }

    private String getSshUrl(String name, int sshPort) throws IOException, KeeperException, InterruptedException {
        return "${zk:" + name + "/ip}:" + sshPort;
    }

    private void registerHttp(Container container) throws Exception {
        boolean httpEnabled = isHttpEnabled();
        boolean httpsEnabled = isHttpsEnabled();

        Configuration configuration = configAdmin.get().getConfiguration(HTTP_PID, null);
        Dictionary<String, Object> dictionary = configuration == null ? null : configuration.getProperties();
        boolean changed = false;

        PortService.Lock lock = null;
        int httpPort = 0;
        int httpsPort = 0;

        if (httpEnabled) {
            try {
                lock = portService.get().acquirePortLock();
                httpPort = getHttpPort(container, lock);
                portService.get().registerPort(container, HTTP_PID, HTTP_BINDING_PORT_KEY, httpPort, lock);
            } finally {
                portService.get().releasePortLock(lock);
            }
            if (configuration != null) {
                changed = updateIfNeeded(dictionary, HTTP_BINDING_PORT_KEY, httpPort);
            }

        }
        if (httpsEnabled) {
            try {
                lock = portService.get().acquirePortLock();
                httpsPort = getHttpsPort(container, lock);
                portService.get().registerPort(container, HTTP_PID, HTTPS_BINDING_PORT_KEY, httpsPort, lock);
            } finally {
                portService.get().releasePortLock(lock);
            }
            changed |= updateIfNeeded(dictionary, HTTPS_BINDING_PORT_KEY, httpsPort);
        }

        String protocol = httpsEnabled && !httpEnabled ? "https" : "http";
        int httpConnectionPort = httpsEnabled && !httpEnabled ? getHttpsConnectionPort(container, httpsPort) : getHttpConnectionPort(container, httpPort);
        String httpUrl = getHttpUrl(protocol, container.getId(), httpConnectionPort);
        setData(curator.get(), CONTAINER_HTTP.getPath(container.getId()), httpUrl);

        if (configuration != null && changed) {
            configuration.update(dictionary);
        }
    }

    private boolean isHttpEnabled() throws IOException {
        Configuration configuration = configAdmin.get().getConfiguration(HTTP_PID, null);
        Dictionary properties = configuration.getProperties();
        if (properties != null && properties.get(HTTP_ENABLED) != null) {
            return Boolean.parseBoolean(String.valueOf(properties.get(HTTP_ENABLED)));
        } else {
            return true;
        }
    }

    private boolean isHttpsEnabled() throws IOException {
        Configuration configuration = configAdmin.get().getConfiguration(HTTP_PID, null);
        Dictionary properties = configuration.getProperties();
        if (properties != null && properties.get(HTTPS_ENABLED) != null) {
            return Boolean.parseBoolean(String.valueOf(properties.get(HTTPS_ENABLED)));
        } else {
            return false;
        }
    }

    private int getHttpPort(Container container, PortService.Lock lock) throws KeeperException, InterruptedException, IOException {
        String portProperty = runtimeProperties.get().getProperty(HTTP_BINDING_PORT_KEY);
        int defaultPort = portProperty != null ? Integer.parseInt(portProperty) : Ports.DEFAULT_HTTP_PORT;
        return getOrAllocatePortForKey(container, HTTP_PID, HTTP_BINDING_PORT_KEY, defaultPort, lock);
    }

    private int getHttpConnectionPort(Container container, int defaultValue) throws KeeperException, InterruptedException, IOException {
        return getPortForKey(container, HTTP_PID, HTTP_CONNECTION_PORT_KEY, defaultValue);
    }

    private int getHttpsPort(Container container, PortService.Lock lock) throws KeeperException, InterruptedException, IOException {
        String portProperty = runtimeProperties.get().getProperty(HTTPS_BINDING_PORT_KEY);
        int defaultPort = portProperty != null ? Integer.parseInt(portProperty) : Ports.DEFAULT_HTTPS_PORT;
        return getOrAllocatePortForKey(container, HTTP_PID, HTTPS_BINDING_PORT_KEY, defaultPort, lock);
    }

    private int getHttpsConnectionPort(Container container, int defaultValue) throws KeeperException, InterruptedException, IOException {
        return getPortForKey(container, HTTP_PID, HTTPS_CONNECTION_PORT_KEY, defaultValue);
    }

    private String getHttpUrl(String protocol, String name, int httpConnectionPort) throws IOException, KeeperException, InterruptedException {
        return protocol + "://${zk:" + name + "/ip}:" + httpConnectionPort;
    }

    /**
     * Returns a port number for the use in the specified pid and key.
     * If the port is already registered it is directly returned. Else the {@link ConfigurationAdmin} or a default value is used.
     * In the later case, the port will be checked against the already registered ports and will be increased, till it doesn't match the used ports.
     */
    private int getOrAllocatePortForKey(Container container, String pid, String key, int defaultValue, PortService.Lock lock) throws IOException, KeeperException, InterruptedException {
        Configuration config = configAdmin.get().getConfiguration(pid, null);
        Set<Integer> unavailable = portService.get().findUsedPortByHost(container, lock);
        int port = portService.get().lookupPort(container, pid, key);
        if (port > 0) {
            return port;
        } else if (config.getProperties() != null && config.getProperties().get(key) != null) {
            try {
                port = Integer.parseInt((String) config.getProperties().get(key));
            } catch (NumberFormatException ex) {
                port = defaultValue;
            }
        } else {
            port = defaultValue;
        }
        while (unavailable.contains(port)) {
            port++;
        }
        return port;
    }

    /**
     * Returns a port number for the use in the specified pid and key.
     * Note: The method doesn't allocate ports, only gets port if configured.
     */
    private int getPortForKey(Container container, String pid, String key, int defaultValue) throws IOException {
        int port = defaultValue;
        Configuration config = configAdmin.get().getConfiguration(pid, null);
        if (config.getProperties() != null && config.getProperties().get(key) != null) {
            try {
                port = Integer.parseInt((String) config.getProperties().get(key));
            } catch (NumberFormatException ex) {
                port = defaultValue;
            }
        } else {
            port = defaultValue;
        }
        return port;
    }

    /**
     * Changes the value in Dictionary if different than existing one. Returns <code>true</code> if the update was needed.
     * @param dictionary
     * @param key
     * @param value
     * @return
     * @throws IOException
     */
    private boolean updateIfNeeded(Dictionary<String, Object> dictionary, String key, Object value) throws IOException {
        if (dictionary != null) {
            if (!String.valueOf(value).equals(dictionary.get(key))) {
                dictionary.put(key, String.valueOf(value));
                // this causes the configuration to be saved, and later configuration.update() may cause old values to rewrite
                // the new ones!
                // see: https://issues.jboss.org/browse/FABRIC-1078?focusedCommentId=12998688&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-12998688
//                configuration.setBundleLocation(null);
                return true;
            }
        }
        return false;
    }

    private String getContainerResolutionPolicy(CuratorFramework zooKeeper, String container) throws Exception {
        String policy = null;
        List<String> validResolverList = Arrays.asList(ZkDefs.VALID_RESOLVERS);
        if (exists(zooKeeper, ZkPath.CONTAINER_RESOLVER.getPath(container)) != null) {
            policy = getStringData(zooKeeper, ZkPath.CONTAINER_RESOLVER.getPath(container));
        } else if (bootstrapConfiguration.get().getLocalResolver() != null && validResolverList.contains(bootstrapConfiguration.get().getLocalResolver())) {
            policy = bootstrapConfiguration.get().getLocalResolver();
        }
        return policy;
    }

    /**
     * Returns a pointer to the container IP based on the global IP policy.
     *
     * @param curator   The curator client to use to read global policy.
     * @param container The name of the container.
     */
    private static String getContainerPointer(CuratorFramework curator, String container) throws Exception {
        String pointer = "${zk:%s/%s}";
        String resolver = "${zk:%s/resolver}";
        return String.format(pointer, container, String.format(resolver, container));
    }

    /**
     * Receives notification of a Configuration that has changed.
     *
     * @param event The <code>ConfigurationEvent</code>.
     */
    @Override
    public void configurationEvent(ConfigurationEvent event) {
        if (isValid()) {
            try {
                Container current = new ImmutableContainerBuilder().id(runtimeIdentity).ip(ip).build();

                RuntimeProperties sysprops = runtimeProperties.get();
                String runtimeIdentity = sysprops.getRuntimeIdentity();
                if (event.getPid().equals(SSH_PID) && event.getType() == ConfigurationEvent.CM_UPDATED) {
                    Configuration config = configAdmin.get().getConfiguration(SSH_PID, null);
                    int sshPort = Integer.parseInt((String) config.getProperties().get(SSH_BINDING_PORT_KEY));
                    int sshConnectionPort = getSshConnectionPort(current, sshPort);
                    String sshUrl = getSshUrl(runtimeIdentity, sshConnectionPort);
                    setData(curator.get(), CONTAINER_SSH.getPath(runtimeIdentity), sshUrl);
                    if (portService.get().lookupPort(current, SSH_PID, SSH_BINDING_PORT_KEY) != sshPort) {
                        portService.get().unregisterPort(current, SSH_PID);
                        portService.get().registerPort(current, SSH_PID, SSH_BINDING_PORT_KEY, sshPort);
                    }
                } else if (event.getPid().equals(HTTP_PID) && event.getType() == ConfigurationEvent.CM_UPDATED) {
                    Configuration config = configAdmin.get().getConfiguration(HTTP_PID, null);
                    boolean httpEnabled = isHttpEnabled();
                    boolean httpsEnabled = isHttpsEnabled();
                    String protocol = httpsEnabled && !httpEnabled ? "https" : "http";
                    int httpConnectionPort = -1;
                    if (httpEnabled) {
                        int httpPort = Integer.parseInt((String) config.getProperties().get(HTTP_BINDING_PORT_KEY));
                        httpConnectionPort = getHttpConnectionPort(current, httpPort);
                        if (portService.get().lookupPort(current, HTTP_PID, HTTP_BINDING_PORT_KEY) != httpPort) {
                            portService.get().unregisterPort(current, HTTP_PID, HTTP_BINDING_PORT_KEY);
                            portService.get().registerPort(current, HTTP_PID, HTTP_BINDING_PORT_KEY, httpPort);
                        }
                    }
                    if (httpsEnabled) {
                        int httpsPort = Integer.parseInt((String) config.getProperties().get(HTTPS_BINDING_PORT_KEY));
                        if (httpConnectionPort == -1) {
                            httpConnectionPort = getHttpsConnectionPort(current, httpsPort);
                        }
                        if (portService.get().lookupPort(current, HTTP_PID, HTTPS_BINDING_PORT_KEY) != httpsPort) {
                            portService.get().unregisterPort(current, HTTP_PID, HTTPS_BINDING_PORT_KEY);
                            portService.get().registerPort(current, HTTP_PID, HTTPS_BINDING_PORT_KEY, httpsPort);
                        }
                    }
                    String httpUrl = getHttpUrl(protocol, runtimeIdentity, httpConnectionPort);
                    setData(curator.get(), CONTAINER_HTTP.getPath(runtimeIdentity), httpUrl);
                } else if (event.getPid().equals(MANAGEMENT_PID) && event.getType() == ConfigurationEvent.CM_UPDATED) {
                    Configuration config = configAdmin.get().getConfiguration(MANAGEMENT_PID, null);
                    int rmiServerPort = Integer.parseInt((String) config.getProperties().get(RMI_SERVER_BINDING_PORT_KEY));
                    int rmiServerConnectionPort = getRmiServerConnectionPort(current, rmiServerPort);
                    int rmiRegistryPort = Integer.parseInt((String) config.getProperties().get(RMI_REGISTRY_BINDING_PORT_KEY));
                    int rmiRegistryConnectionPort = getRmiRegistryConnectionPort(current, rmiRegistryPort);
                    String jmxUrl = getJmxUrl(runtimeIdentity, rmiServerConnectionPort, rmiRegistryConnectionPort);
                    setData(curator.get(), CONTAINER_JMX.getPath(runtimeIdentity), jmxUrl);
                    //Whenever the JMX URL changes we need to make sure that the java.rmi.server.hostname points to a valid address.
                    System.setProperty(SystemProperties.JAVA_RMI_SERVER_HOSTNAME, current.getIp());
                    if (portService.get().lookupPort(current, MANAGEMENT_PID, RMI_REGISTRY_BINDING_PORT_KEY) != rmiRegistryPort
                            || portService.get().lookupPort(current, MANAGEMENT_PID, RMI_SERVER_BINDING_PORT_KEY) != rmiServerPort) {
                        portService.get().unregisterPort(current, MANAGEMENT_PID);
                        portService.get().registerPort(current, MANAGEMENT_PID, RMI_SERVER_BINDING_PORT_KEY, rmiServerPort);
                        portService.get().registerPort(current, MANAGEMENT_PID, RMI_REGISTRY_BINDING_PORT_KEY, rmiRegistryPort);
                    }
                } else if (event.getPid().equals(AGENT_PID) && event.getType() == ConfigurationEvent.CM_UPDATED) {
                    Configuration config = configAdmin.get().getConfiguration(AGENT_PID, null);
                    String disabled = (String) config.getProperties().get(AGENT_DISABLED);
                    boolean managed = !"true".equalsIgnoreCase(disabled);
                    setData(curator.get(), CONTAINER_MANAGED.getPath(runtimeIdentity), Boolean.toString(managed));
                }
            } catch (Exception ex) {
                LOGGER.error("Cannot reconfigure container", ex);
            }
        }
    }

    void bindCurator(CuratorFramework curator) {
        this.curator.bind(curator);
    }

    void unbindCurator(CuratorFramework curator) {
        this.curator.unbind(curator);
    }

    void bindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.bind(service);
    }

    void unbindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.unbind(service);
    }

    void bindRuntimeProperties(RuntimeProperties service) {
        this.runtimeProperties.bind(service);
    }

    void unbindRuntimeProperties(RuntimeProperties service) {
        this.runtimeProperties.unbind(service);
    }

    void bindPortService(PortService service) {
        this.portService.bind(service);
    }

    void unbindPortService(PortService service) {
        this.portService.unbind(service);
    }

    void bindGeoLocationService(GeoLocationService service) {
        this.geoLocationService.bind(service);
    }

    void unbindGeoLocationService(GeoLocationService service) {
        this.geoLocationService.unbind(service);
    }

    void bindBootstrapConfiguration(BootstrapConfiguration service) {
        this.bootstrapConfiguration.bind(service);
    }

    void unbindBootstrapConfiguration(BootstrapConfiguration service) {
        this.bootstrapConfiguration.unbind(service);
    }
}
