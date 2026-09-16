package com.mamtrex.hospital.infrastructure;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Reusable real-PostgreSQL fixture for Phase 4 migration/concurrency
 * integration evidence (plan Task 3, step 2). One pinned-major PostgreSQL
 * container is shared per test JVM; every test that needs write isolation
 * gets its own freshly created disposable database inside that container
 * (created and dropped by this fixture — the test process owns the
 * container and every database it makes).
 *
 * <p>The persistence infrastructure under test (Flyway, PostgreSQL) is
 * never mocked: these are real migrations against a real server. The
 * container image is pinned to a major version only (postgres:16-alpine)
 * so patch updates stay deterministic while the major stays reviewable.</p>
 */
public final class PostgresContainerSupport {

    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:16-alpine");

    private static volatile PostgreSQLContainer<?> container;

    /** Separate container whose loopback host port binding is STATIC, so the mapping survives docker stop + docker start. */
    private static volatile PostgreSQLContainer<?> restartableContainer;

    private PostgresContainerSupport() {
    }

    /** The shared per-JVM container, started on first use. */
    public static PostgreSQLContainer<?> container() {
        PostgreSQLContainer<?> local = container;
        if (local == null) {
            synchronized (PostgresContainerSupport.class) {
                local = container;
                if (local == null) {
                    local = new PostgreSQLContainer<>(IMAGE)
                            .withDatabaseName("medicore_phase4_harness")
                            .withUsername("medicore")
                            .withPassword("medicore");
                    local.start();
                    container = local;
                }
            }
        }
        return local;
    }

    /**
     * A fresh, empty, explicitly disposable database inside the shared
     * container. The name always carries the phase's disposable prefix so
     * no operator could mistake it for shared state. The caller never
     * cleans it up individually; the whole container is process-owned and
     * dies with the JVM.
     */
    public static DisposableDatabase newIsolatedDatabase() {
        return newIsolatedDatabase(container());
    }

    /** A fresh disposable database inside the restartable (static-binding) container. */
    public static DisposableDatabase newIsolatedDatabase(PostgreSQLContainer<?> pg) {
        String dbName = "medicore_phase4_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + dbName);
        } catch (SQLException failedToCreate) {
            throw new IllegalStateException("Could not create disposable test database", failedToCreate);
        }
        return new DisposableDatabase(pg.getHost(), pg.getMappedPort(5432), dbName,
                pg.getUsername(), pg.getPassword());
    }

    /**
     * The shared per-JVM RESTARTABLE container: identical pinned image, but its
     * 5432 port is bound to an explicit loopback host port chosen free at start.
     * This is the only configuration under which a container's host port mapping
     * is guaranteed to survive docker stop + docker start (verified: dynamic
     * mappings do NOT survive restart on this daemon, so an application pointing
     * at a dynamic port could never recover). Process-owned like the default
     * harness container; dies with the JVM via Testcontainers/Ryuk.
     */
    public static PostgreSQLContainer<?> restartableContainer() {
        PostgreSQLContainer<?> local = restartableContainer;
        if (local == null) {
            synchronized (PostgresContainerSupport.class) {
                local = restartableContainer;
                if (local == null) {
                    int fixedLoopbackPort = findFreeLoopbackPort();
                    local = new PostgreSQLContainer<>(IMAGE)
                            .withDatabaseName("medicore_phase4_harness_restartable")
                            .withUsername("medicore")
                            .withPassword("medicore")
                            .withCreateContainerCmdModifier(cmd -> bindLoopbackPort(cmd, fixedLoopbackPort));
                    local.start();
                    restartableContainer = local;
                }
            }
        }
        return restartableContainer;
    }

    private static void bindLoopbackPort(CreateContainerCmd cmd, int hostPort) {
        cmd.withHostConfig(cmd.getHostConfig().withPortBindings(
                new Ports(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", hostPort), ExposedPort.tcp(5432)))));
    }

    /** A currently-free loopback port; used immediately for the explicit binding. */
    private static int findFreeLoopbackPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (Exception failedToFind) {
            throw new IllegalStateException("Could not find a free loopback port", failedToFind);
        }
    }

    /** Coordinates of one disposable database inside the shared container. */
    public record DisposableDatabase(String host, int port, String name, String user, String password) {

        public String jdbcUrl() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + name;
        }
    }
}
