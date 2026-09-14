package com.mamtrex.hospital.infrastructure;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
        PostgreSQLContainer<?> pg = container();
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

    /** Coordinates of one disposable database inside the shared container. */
    public record DisposableDatabase(String host, int port, String name, String user, String password) {

        public String jdbcUrl() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + name;
        }
    }
}
