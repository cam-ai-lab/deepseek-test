package com.example.quotes.blackbox.config;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.test.context.ContextConfiguration;

/**
 * The one class Cucumber's Spring integration is allowed to find in the glue path.
 *
 * <p>It deliberately carries <em>no</em> Spring stereotype. cucumber-spring refuses to work with a
 * glue class that is also a Spring component, because Spring would create one instance and Cucumber
 * another - so this class only points at where the beans live, and {@link BlackboxConfig} holds them.
 * Merging the two is the obvious simplification and it does not work.
 */
@CucumberContextConfiguration
@ContextConfiguration(classes = BlackboxConfig.class)
public class CucumberSpringConfiguration {
}
