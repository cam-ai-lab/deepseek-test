package com.example.quotes.quote;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.example.quotes.QuotesApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * UNIT tier: architecture rules.
 *
 * <p>These run in milliseconds because they analyse bytecode rather than starting anything, and
 * they are the only guard that keeps working as the team and the codebase grow. Everything here
 * is a rule the codebase already satisfies - the point is to stop it regressing, not to fix it.
 */
@DisplayName("architecture")
class ArchitectureTest {

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter().importPath(mainClassesDirectory());

    static {
        System.out.printf("ArchitectureTest imported %d production classes from %s%n", MAIN_CLASSES.size(),
                mainClassesDirectory());
    }

    private static final String RATE = "com.example.quotes.rate..";
    private static final String QUOTE = "com.example.quotes.quote..";
    private static final String WEB = "com.example.quotes.web..";

    @Test
    void the_rate_adapter_does_not_know_about_quoting() {
        noClasses().that().resideInAPackage(RATE)
                .should().dependOnClassesThat().resideInAPackage(QUOTE)
                .check(MAIN_CLASSES);
    }

    @Test
    void the_rate_adapter_does_not_know_about_the_web_layer() {
        noClasses().that().resideInAPackage(RATE)
                .should().dependOnClassesThat().resideInAPackage(WEB)
                .check(MAIN_CLASSES);
    }

    @Test
    void the_quote_domain_does_not_depend_on_the_web_layer() {
        noClasses().that().resideInAPackage(QUOTE)
                .should().dependOnClassesThat().resideInAPackage(WEB)
                .check(MAIN_CLASSES);
    }

    /**
     * Imports only compiled production classes, so a test class can never accidentally satisfy -
     * or violate - a production rule. Gradle supplies the directory; the classpath fallback keeps
     * the test runnable from an IDE.
     */
    private static Path mainClassesDirectory() {
        String configured = System.getProperty("mainClassesDir");
        if (configured != null) {
            return Paths.get(configured);
        }
        try {
            return Paths.get(QuotesApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        }
        catch (Exception ex) {
            throw new IllegalStateException("Cannot locate compiled main classes on the classpath", ex);
        }
    }
}
