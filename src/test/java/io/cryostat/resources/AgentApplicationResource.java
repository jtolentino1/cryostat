/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.resources;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.apache.commons.lang3.StringUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy;
import org.testcontainers.utility.DockerImageName;

public class AgentApplicationResource
        implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {

    private static final String DEFAULT_IMAGE =
            "quay.io/redhat-java-monitoring/quarkus-cryostat-agent:latest";
    private static final String HOST_GATEWAY_ALIAS = "host.docker.internal";
    public static final int PORT = 9977;
    public static final String ALIAS = "quarkus-cryostat-agent";

    protected Map<String, String> getEnvMap() {
        return new HashMap<>(
                Map.of(
                        "JAVA_OPTS_APPEND",
                        """
                        -javaagent:/deployments/app/cryostat-agent.jar
                        -javaagent:/deployments/app/jmc-agent.jar
                        -javaagent:/deployments/app/async-profiler.jar
                        -Djava.util.logging.manager=org.jboss.logmanager.LogManager
                        -Dio.cryostat.agent.shaded.org.slf4j.simpleLogger.defaultLogLevel=warn
                        """
                                .replace("\n", " ")
                                .strip(),
                        "QUARKUS_HTTP_PORT",
                        "9898",
                        "CRYOSTAT_AGENT_APP_NAME",
                        "quarkus-cryostat-agent",
                        "CRYOSTAT_AGENT_WEBCLIENT_TLS_REQUIRED",
                        "false",
                        "CRYOSTAT_AGENT_WEBSERVER_HOST",
                        "0.0.0.0",
                        "CRYOSTAT_AGENT_WEBSERVER_PORT",
                        Integer.toString(PORT),
                        "CRYOSTAT_AGENT_BASEURI_RANGE",
                        "public",
                        "CRYOSTAT_AGENT_API_WRITES_ENABLED",
                        "true"));
    }

    private Optional<String> containerNetworkId;
    private GenericContainer<?> container;

    @SuppressWarnings("resource")
    @Override
    public Map<String, String> start() {
        int cryostatPort = findFreePort();
        int hostAgentPort = findFreePort();

        Optional<Network> network =
                containerNetworkId.map(
                        id ->
                                new Network() {
                                    @Override
                                    public String getId() {
                                        return id;
                                    }

                                    @Override
                                    public void close() {}
                                });

        String img =
                Optional.ofNullable(System.getenv("QUARKUS_TEST_IMAGE"))
                        .filter(StringUtils::isNotBlank)
                        .orElse(DEFAULT_IMAGE);
        String callbackHost = resolveHostGateway(img, network);
        this.container =
                new GenericContainer<>(DockerImageName.parse(img))
                        .withExposedPorts(PORT)
                        .withEnv(getEnvMap())
                        .withNetworkAliases(ALIAS)
                        .withExtraHost(HOST_GATEWAY_ALIAS, "host-gateway")
                        .waitingFor(new HostPortWaitStrategy().forPorts(PORT))
                        .withStartupAttempts(3)
                        .withCreateContainerCmdModifier(
                                cmd ->
                                        cmd.getHostConfig()
                                                .withCpuShares(512)
                                                .withMemory(256L * 1024L * 1024L));
        network.ifPresent(container::withNetwork);

        container.setPortBindings(List.of(String.format("%d:%d", hostAgentPort, PORT)));
        container.addEnv(
                "CRYOSTAT_AGENT_BASEURI",
                String.format("http://%s:%d/", HOST_GATEWAY_ALIAS, cryostatPort));
        container.addEnv(
                "CRYOSTAT_AGENT_CALLBACK",
                String.format("http://%s:%d/", toUriHost(callbackHost), hostAgentPort));

        container.start();

        return Map.of(
                "cryostat.agent.tls.required",
                "false",
                "quarkus.http.port",
                Integer.toString(cryostatPort),
                "quarkus.http.test-port",
                Integer.toString(cryostatPort));
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
            container.close();
        }
    }

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        containerNetworkId = context.containerNetworkId();
    }

    private static String resolveHostGateway(String img, Optional<Network> network) {
        try (GenericContainer<?> resolver =
                new GenericContainer<>(DockerImageName.parse(img))
                        .withExtraHost(HOST_GATEWAY_ALIAS, "host-gateway")
                        .withCreateContainerCmdModifier(
                                cmd -> cmd.withEntrypoint("/bin/sh").withCmd("-c", "sleep 30"))) {
            network.ifPresent(resolver::withNetwork);
            resolver.start();

            var result = resolver.execInContainer("getent", "hosts", HOST_GATEWAY_ALIAS);
            if (result.getExitCode() != 0 || StringUtils.isBlank(result.getStdout())) {
                throw new IllegalStateException(
                        String.format(
                                "Failed to resolve %s: %s",
                                HOST_GATEWAY_ALIAS, result.getStderr().strip()));
            }
            return result.getStdout().strip().split("\\s+")[0];
        } catch (IOException e) {
            throw new RuntimeException("Failed to resolve the container host gateway", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while resolving the container host gateway", e);
        }
    }

    private static String toUriHost(String host) {
        return host.contains(":") ? String.format("[%s]", host) : host;
    }

    private static int findFreePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find free port", e);
        }
    }
}
