package com.example.quotes.blackbox.stack;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.testcontainers.containers.ComposeContainer;
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

    private static ComposeContainer stack;

    private BlackboxStack() {
    }

    /** Starts the stack if it is not already up. Safe to call from every scenario. */
    public static synchronized void ensureStarted() {
        if (stack != null) {
            return;
        }
        start(composeFile());
    }

    /**
     * The compose file: the {@code blackbox.composeFile} system property when the build sets it (the
     * Cucumber task does), otherwise the first {@code compose.blackbox.yaml} found walking up from the
     * working directory (the Gatling run, or an IDE).
     */
    private static File composeFile() {
        String configured = System.getProperty("blackbox.composeFile");
        if (configured != null) {
            return new File(configured);
        }
        for (File dir = new File(System.getProperty("user.dir")).getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
            File candidate = new File(dir, "compose.blackbox.yaml");
            if (candidate.isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot find compose.blackbox.yaml: set the system property "
                + "'blackbox.composeFile' or run from inside the repository.");
    }

    /**
     * ComposeContainer is Testcontainers' Compose v2 support. The older DockerComposeContainer looks
     * for v1 container names ({@code project_app_1}); Compose v2 names them {@code project-app-1}, so
     * with v2 it reported the running app as "not running". ComposeContainer runs {@code docker
     * compose} in a helper container, so no {@code docker-compose} binary is needed on the host.
     */
    private static void start(File composeFile) {
        ComposeContainer container = new ComposeContainer(composeFile)
                // quotes:blackbox exists only in the local daemon, built by :dockerImage. The default
                // is to pull every image first, which would look for it on Docker Hub and fail.
                .withPull(false)
                .withExposedService(APP, APP_PORT,
                        Wait.forHttp("/actuator/health/readiness")
                                .forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(3)))
                .withExposedService(WIREMOCK, WIREMOCK_PORT,
                        Wait.forHttp("/__admin/health").forStatusCode(200))
                // The app will not start until Postgres is healthy (depends_on), and the app's
                // readiness is what is waited on above.
                .withExposedService(POSTGRES, POSTGRES_PORT)
                .withRemoveVolumes(true)
                .withStartupTimeout(Duration.ofMinutes(5))
                // Stream container output into the build log while the stack runs. When a scenario
                // fails, the application's own exception is usually the fastest explanation.
                .withLogConsumer(APP, BlackboxStack::log)
                .withLogConsumer(WIREMOCK, BlackboxStack::log);
        try {
            container.start();
        }
        catch (RuntimeException ex) {
            dumpLogs();
            throw ex;
        }
        stack = container;
        Runtime.getRuntime().addShutdownHook(new Thread(BlackboxStack::stopQuietly));
    }

    /**
     * Prints every compose service's logs when the stack fails to come up.
     *
     * <p>Without this the failure is "container not running" plus a teardown, which tells you
     * nothing about why. The application's own exception is almost always the answer.
     *
     * <p>{@code docker compose logs} cannot be used here: Testcontainers runs the stack under a random
     * project name, so a plain {@code docker compose -f ... logs} looks at a project that does not
     * exist and prints nothing. Instead, find the containers by the service label Compose puts on
     * every container it creates, including ones that have already exited.
     */
    private static void dumpLogs() {
        System.out.println("--- container logs (the stack did not come up) ---");
        for (String service : new String[] {APP, WIREMOCK, POSTGRES}) {
            String ids = run("docker", "ps", "-a", "-q", "--filter", "label=com.docker.compose.service=" + service);
            if (ids.startsWith(RUN_FAILED)) {
                System.out.println(ids);
                break;
            }
            for (String container : ids.split("\\s+")) {
                if (!container.isBlank()) {
                    System.out.println("--- " + service + " (" + container + ") ---");
                    System.out.println(run("docker", "logs", "--tail", "200", container));
                }
            }
        }
        System.out.println("--- end of container logs ---");
    }

    private static final String RUN_FAILED = "could not run ";

    private static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            return output;
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        }
        catch (Exception ex) {
            return RUN_FAILED + String.join(" ", command) + ": " + ex;
        }
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
