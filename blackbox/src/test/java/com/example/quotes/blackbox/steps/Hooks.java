package com.example.quotes.blackbox.steps;

import com.example.quotes.blackbox.stack.BlackboxStack;
import com.example.quotes.blackbox.support.RateStub;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Suite-wide hooks.
 *
 * <p>The stack is started once, for the whole JVM, rather than per scenario - {@code docker compose
 * up} takes tens of seconds and doing it per scenario would dominate the run.
 */
public class Hooks {

    @Autowired
    private RateStub rateStub;

    @BeforeAll
    public static void startStack() {
        BlackboxStack.ensureStarted();
    }

    /**
     * Stubs live on the shared WireMock container, so a stub left by one scenario would still be
     * there for the next. Clearing both stubs and the request journal before each scenario is what
     * makes them independent - and it is why they run serially.
     */
    @Before
    public void resetStubs() {
        this.rateStub.reset();
    }
}
