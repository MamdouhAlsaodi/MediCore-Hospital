package com.mamtrex.hospital.observability;

import com.mamtrex.hospital.TestRuntimeSecrets;
import com.mamtrex.hospital.infrastructure.PostgresContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T051: the live liveness/readiness contract under real PostgreSQL loss and
 * recovery, against the shipped PostgreSQL review profile (FR-008, SC-006).
 *
 * <p>The application is the same process for all three phases — the probe
 * never rebuilds or restarts it. The database is a process-owned, explicitly
 * disposable Testcontainers PostgreSQL whose container is genuinely stopped
 * (docker stop) and started again (docker start), so the database-loss and
 * recovery are real infrastructure events, never mocked persistence. The
 * container uses an EXPLICIT loopback port binding
 * ({@code PostgresContainerSupport.restartableContainer()}): a dynamic host
 * mapping does not survive a container restart on this daemon (probe
 * evidence), which would make recovery impossible for an application whose
 * JDBC URL is fixed at startup.
 *
 * <p>Contract under test:
 * <ol>
 *   <li>with a healthy database, liveness and readiness are both healthy;</li>
 *   <li>while the database is down, liveness stays healthy (process-only)
 *       and readiness fails — readiness must actually consult the database;</li>
 *   <li>after the database returns, readiness recovers to healthy on the
 *       same live process.</li>
 * </ol>
 *
 * <p>Probes are enabled here as an explicit platform capability switch; the
 * shipped YAML contract for probes and health groups is asserted separately
 * by {@code ObservabilityConfigContractTest}, so this test exercises the
 * real profile configuration end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=postgres",
        "management.endpoint.health.probes.enabled=true"
})
class LivenessReadinessDbLossIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void reviewEnvironment(DynamicPropertyRegistry registry) {
        DB = PostgresContainerSupport.newIsolatedDatabase(PostgresContainerSupport.restartableContainer());
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", DB::user);
        registry.add("spring.datasource.password", DB::password);
        registry.add("hospital.jwt.secret", TestRuntimeSecrets::jwtSecret);
        registry.add("HOSPITAL_ADMIN_PASSWORD", TestRuntimeSecrets::accountPassword);
    }

    private static PostgresContainerSupport.DisposableDatabase DB;

    /** Bounded poll: wait until the endpoint answers with the expected status; fail on timeout. */
    private void awaitStatus(String path, int expected, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (status(path) == expected) {
                    return;
                }
            } catch (Exception attemptFailure) {
                lastFailure = attemptFailure;
            }
            Thread.sleep(250);
        }
        fail("endpoint " + path + " never reached status " + expected + " within "
                + timeoutMillis + "ms" + (lastFailure == null ? "" : "; last failure: " + lastFailure));
    }

    private int status(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<Void> response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    @Test
    void databaseLossKeepsLivenessAliveFailsReadinessAndRecoversOnSameProcess() throws Exception {
        // Phase 1: healthy database — both probes must be truthful and healthy.
        awaitStatus("/actuator/health/liveness", 200, 60_000);
        awaitStatus("/actuator/health/readiness", 200, 60_000);

        // Phase 2: REAL database loss — stop the process-owned disposable container.
        var container = PostgresContainerSupport.restartableContainer();
        var docker = DockerClientFactory.instance().client();
        String containerId = container.getContainerId();
        docker.stopContainerCmd(containerId).withTimeout(2).exec();
        {
            long deadline = System.currentTimeMillis() + 60_000;
            boolean readinessFailed = false;
            while (System.currentTimeMillis() < deadline) {
                assertEquals(200, status("/actuator/health/liveness"),
                        "liveness must stay healthy (process-only) during database loss");
                if (status("/actuator/health/readiness") != 200) {
                    readinessFailed = true;
                    break;
                }
                Thread.sleep(250);
            }
            assertTrue(readinessFailed,
                    "readiness must fail while the database is unavailable — the readiness "
                            + "group must actually consult the database");
        }

        // Phase 3: recovery — start the same container; the application is never rebuilt.
        docker.startContainerCmd(containerId).exec();

        // The database itself must accept connections again before readiness can.
        // This poll turns any infrastructure-side failure into explicit evidence
        // instead of an opaque readiness timeout.
        long dbDeadline = System.currentTimeMillis() + 90_000;
        SQLException lastDbFailure = null;
        int diagnosticsPrinted = 0;
        while (System.currentTimeMillis() < dbDeadline) {
            try (Connection ignored = DriverManager.getConnection(
                    DB.jdbcUrl(), DB.user(), DB.password())) {
                lastDbFailure = null;
                break;
            } catch (SQLException attemptFailure) {
                lastDbFailure = attemptFailure;
                if (diagnosticsPrinted++ % 20 == 0) {
                    try {
                        var inspect = docker.inspectContainerCmd(containerId).exec();
                        var state = inspect.getState();
                        System.err.println("[db-loss-diagnostic] container status=" + state.getStatus()
                                + " running=" + state.getRunning() + " exitCode=" + state.getExitCode()
                                + " ports=" + inspect.getNetworkSettings().getPorts().getBindings());
                        var recentLogs = new StringBuilder();
                        docker.logContainerCmd(containerId).withStdOut(true).withStdErr(true).withTail(8)
                                .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<com.github.dockerjava.api.model.Frame>() {
                                    @Override
                                    public void onNext(com.github.dockerjava.api.model.Frame frame) {
                                        recentLogs.append(new String(frame.getPayload(),
                                                java.nio.charset.StandardCharsets.UTF_8));
                                    }
                                });
                        Thread.sleep(600);
                        System.err.println("[db-loss-diagnostic] recent logs: "
                                + recentLogs.toString().replace("\n", " | "));
                    } catch (Exception inspectFailure) {
                        System.err.println("[db-loss-diagnostic] inspect failed: " + inspectFailure);
                    }
                }
                Thread.sleep(500);
            }
        }
        if (lastDbFailure != null) {
            fail("the disposable PostgreSQL container never re-accepted connections within 90s: "
                    + lastDbFailure);
        }

        awaitStatus("/actuator/health/readiness", 200, 120_000);
        assertEquals(200, status("/actuator/health/liveness"),
                "liveness must stay healthy across the whole loss/recovery cycle");
    }
}
