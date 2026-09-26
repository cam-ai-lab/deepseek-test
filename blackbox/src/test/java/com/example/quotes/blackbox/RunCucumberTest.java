package com.example.quotes.blackbox;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;

/**
 * The entry point. JUnit Platform runs this class, which hands discovery to the Cucumber engine,
 * which finds the {@code .feature} files and the step definitions in this package.
 *
 * <p>Everything else - the plugins that produce the HTML and JUnit XML reports - is in
 * {@code junit-platform.properties}.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.example.quotes.blackbox")
public class RunCucumberTest {
}
