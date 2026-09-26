package com.example.quotes.blackbox.stack;

import java.io.File;
import java.time.Duration;

import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Starts the real service, its database and its stubbed upstream through {@code docker compose}, and
 * exposes the ports they ended up on.
 *
 * <p>This is the only thing the black-box suite and the Gatling simulation share, which is why it
 * lives in {@code src/main} rather than in either test source set.
 *
 * <p>Three properties worth knowing:
 *
 * <ul>
 * <li><b>Lazy and shared.</b> The stack starts the first time it is touched and stays up for the
 * JVM. Something that starts a compose stack per test class would spend all its time in
 * {@code docker compose up}.
 * <li><b>No fixed host ports.</b> The compose file publishes nothing; Testcontainers adds a random
 * host mapping for exactly the services named here. A black-box run therefore cannot collide with a
 * development stack that already holds 5432.
 * <li><b>It never loads any application code.</b> Everything is reached over HTTP or JDBC, which is
 * what makes the suite black-box rather than in-process.
 * </ul>
 *
 * <p>The compose file is located through a system property rather than a relative path, because the
 * test working directory is the module directory and guessing upward from there is fragile.
 */
public final class BlackboxStack {

    public static final String APP = "app";

    public static final String WIREMOCK = "wiremock";

    public static final String POSTGRES = "postgres";

    private static final int APP_PORT = 8080;

    private static final int WIREMOCK_PORT = 8080;

    private static final int POSTGRES_PORT = 5432;

    private static final String DEFAULT_DATABASE = "quotes";

    // Raw type: DockerComposeContainer is declared as DockerComposeContainer<SELF extends
    // DockerComposeContainer<SELF>>, which a wildcard cannot satisfy at a call site, so chaining
    // through it does not typecheck. The methods used here take no type parameters of their own.
    @SuppressWarnings("rawtypes")
    private static DockerComposeContainer stack;

    private BlackboxStack() {
    }

    /** Starts the stack if it is not already up. Safe to call from every scenario. */
    public static synchronized void ensureStarted() {
        if (stack != null) {
            return;
        }
        String composeFile = System.getProperty("blackbox.composeFile");
        if (composeFile == null) {
            throw new IllegalStateException(
                    "System property 'blackbox.composeFile' is not set. It is set by blackbox/build.gradle.kts.");
        }
        start(new File(composeFile));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void start(File composeFile) {
        DockerComposeContainer container = new DockerComposeContainer(composeFile);
        // Each call is a statement rather than a chain, for the raw-type reason above.
        //
        // The wait strategy is supplied per service rather than via `withOptions("--wait")`:
        // Testcontainers builds the command as `docker compose <options> up -d`, and `--wait` is an
        // option of `up` rather than a global one, so it produced `--wait up -d` and compose
        // rejected the command outright.
        container.withExposedService(APP, APP_PORT,
                Wait.forHttp("/actuator/health/readiness")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        container.withExposedService(WIREMOCK, WIREMOCK_PORT,
                Wait.forHttp("/__admin/health").forStatusCode(200));
        // Postgres needs no HTTP check: the application will not start until it is healthy, and the
        // app's readiness is what is actually waited on above.
        container.withExposedService(POSTGRES, POSTGRES_PORT);
        container.withRemoveVolumes(true);
        container.withStartupTimeout(Duration.ofMinutes(5));
        // Pipe container output into the build log. When a scenario fails, the application's own
        // exception is usually the fastest explanation and Testcontainers is about to delete it.
        // The frame is cast because the raw receiver erases the parameter to Consumer<Object>.
        container.withLogConsumer(APP, frame -> log((OutputFrame) frame));
        container.withLogConsumer(WIREMOCK, frame -> log((OutputFrame) frame));
        container.start();
        stack = container;
        Runtime.getRuntime().addShutdownHook(new Thread(BlackboxStack::stopQuietly));
    }

    private static void log(OutputFrame frame) {
        System.out.print(frame.getUtf8String());
    }

    private static void stopQuietly() {
        try {
            if (stack != null) {
                stack.stop();
            }
        }
        catch (RuntimeException ignored) {
            // The JVM is going away; a failure to tear down is not worth reporting over it.
        }
    }

    /** Base URL of the running service. */
    public static String baseUrl() {
        ensureStarted();
        return "http://" + host(APP, APP_PORT) + ":" + port(APP, APP_PORT);
    }

    /** Base URL of the WireMock admin API. */
    public static String wiremockAdminUrl() {
        ensureStarted();
        return "http://" + host(WIREMOCK, WIREMOCK_PORT) + ":" + port(WIREMOCK, WIREMOCK_PORT);
    }

    /** JDBC URL for the database. The suite connects with a read-only role, see QuoteDb. */
    public static String jdbcUrl() {
        ensureStarted();
        return "jdbc:postgresql://" + host(POSTGRES, POSTGRES_PORT) + ":" + port(POSTGRES, POSTGRES_PORT)
                + "/" + DEFAULT_DATABASE;
    }

    private static String host(String service, int servicePort) {
        return stack.getServiceHost(service, servicePort);
    }

    private static int port(String service, int servicePort) {
        return stack.getServicePort(service, servicePort);
    }
}
