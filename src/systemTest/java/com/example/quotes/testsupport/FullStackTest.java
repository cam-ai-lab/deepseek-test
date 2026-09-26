package com.example.quotes.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The one canonical full-stack Spring configuration.
 *
 * <p>This is the context-economy mechanism, not a convenience. Spring caches contexts by a key
 * built from the merged configuration, so if two test classes reach for {@code @SpringBootTest}
 * with slightly different annotations they get two contexts and two full application boots.
 * Routing every black-box test through this single composed annotation makes that impossible by
 * construction.
 *
 * <p>Note what is deliberately absent: {@code @MockitoBean} and {@code @DynamicPropertySource}
 * variations. Both are part of the cache key, so either one on a single test class would split
 * the context. Remote dependencies are stubbed over HTTP instead.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
public @interface FullStackTest {
}
