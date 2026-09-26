# Appendix E — Further reading

These are well-established sources; check for current editions and documentation versions.

## Foundations

- **Gerard Meszaros, *xUnit Test Patterns*** — the origin of the test-double vocabulary in chapter 4,
  and a catalogue of test smells.
- **Kent Beck, *Test-Driven Development: By Example*** — the short classic on writing tests first.
- **Steve Freeman and Nat Pryce, *Growing Object-Oriented Software, Guided by Tests*** — outside-in
  development, and the best discussion of mocking done well.
- **Vladimir Khorikov, *Unit Testing: Principles, Practices, and Patterns*** — a rigorous treatment of
  what makes tests valuable, including the costs of over-mocking.

## Strategy and shapes

- **Martin Fowler, "The Practical Test Pyramid"** (martinfowler.com, by Ham Vocke) — a hands-on tour
  of the pyramid with a Spring Boot example.
- **Spotify Engineering, "Testing of Microservices"** — the honeycomb.
- **Kent C. Dodds, "The Testing Trophy and Testing Classifications"** — the trophy, for front ends.
- **Google, *Software Engineering at Google*, chapters on testing** — test sizes, flakiness at scale,
  and larger tests.

## Tools used in the lab

- **JUnit 5 User Guide** — parameterised tests, tags, the JUnit Platform.
- **AssertJ documentation** — especially `BigDecimal` assertions and soft assertions.
- **Spring Boot reference, "Testing"** — slices, the context cache, `@DynamicPropertySource`.
- **Testcontainers documentation** — singleton containers, Compose support, wait strategies.
- **WireMock documentation** — stubbing, verification, the admin API.
- **ArchUnit User Guide** — rules, layers, freezing violations in legacy code.
- **Cucumber documentation** — Gherkin reference, Cucumber expressions, `cucumber-spring`, the JUnit
  Platform engine.
- **REST-assured documentation** — request specifications and JSON path configuration.
- **Gatling documentation** — the Java DSL, injection profiles, assertions.
- **jqwik User Guide** — properties, generators, shrinking.
- **Pact documentation** — consumer-driven contracts and `can-i-deploy`.
- **Schemathesis documentation** — fuzzing from OpenAPI.
- **PIT (Pitest)** — mutation testing for the JVM.

## Behaviour-driven development

- **Dan North, "Introducing BDD"** — the original essay.
- **Gojko Adzic, *Specification by Example*** — examples as a collaboration tool.
- **Seb Rose, Matt Wynne and Aslak Hellesøy, *The Cucumber Book*** — practical Gherkin and step design.

## Performance

- **Gil Tene, "How NOT to Measure Latency"** (talk) — percentiles and coordinated omission.
- **Brendan Gregg, *Systems Performance*** — when you need to know *why* it's slow.

## Learning science behind this book's structure

- **Peter C. Brown, Henry L. Roediger III and Mark A. McDaniel, *Make It Stick*** — retrieval practice,
  spacing and interleaving.
- **Richard E. Mayer, *Multimedia Learning*** — why words plus pictures beat words alone.
