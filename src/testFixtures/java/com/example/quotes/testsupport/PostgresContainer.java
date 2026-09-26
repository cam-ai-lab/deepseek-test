package com.example.quotes.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container for the whole test run, started the first time it is touched and then
 * shared.
 *
 * <p>This is the "singleton container" pattern. The obvious alternative - putting {@code @Container}
 * on a field in each test class - starts and stops a container <em>per test class</em>, which is the
 * database equivalent of starting the application over again for every class.
 *
 * <p>Two deliberate properties:
 *
 * <ul>
 * <li><b>Lazy.</b> Nothing starts until a method is called, so a tier that never needs a database
 * (the unit tier compiles against this class too) never pays for one and never needs Docker.
 * <li><b>No Testcontainers type in the public API.</b> Testcontainers is a
 * {@code testFixturesImplementation} dependency, so callers see plain strings and booleans and never
 * need the library on their own compile classpath.
 * </ul>
 */
public final class PostgresContainer {

    private static PostgreSQLContainer container;

    private PostgresContainer() {
    }

    private static synchronized PostgreSQLContainer container() {
        if (container == null) {
            container = new PostgreSQLContainer("postgres:17-alpine");
            container.start();
            Runtime.getRuntime().addShutdownHook(new Thread(PostgresContainer::stopQuietly));
        }
        return container;
    }

    private static void stopQuietly() {
        try {
            if (container != null) {
                container.stop();
            }
        }
        catch (RuntimeException ignored) {
            // The JVM is going away anyway.
        }
    }

    public static String jdbcUrl() {
        return container().getJdbcUrl();
    }

    public static String username() {
        return container().getUsername();
    }

    public static String password() {
        return container().getPassword();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), username(), password());
    }

    /** Asserts, from outside Hibernate, that we are genuinely talking to PostgreSQL. */
    public static String productName() throws SQLException {
        try (Connection connection = connect()) {
            return connection.getMetaData().getDatabaseProductName();
        }
    }

    /** Asserts that a migration actually created a table, rather than Hibernate inferring one. */
    public static boolean tableExists(String table) throws SQLException {
        try (Connection connection = connect();
                ResultSet tables = connection.getMetaData().getTables(null, "public", table, null)) {
            return tables.next();
        }
    }
}
