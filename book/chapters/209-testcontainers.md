# 9 Real dependencies with Testcontainers

::: {.callout .covers}
This chapter covers

- Why an in-memory database is a test double, and what it hides
- Flyway owning the schema, and `ddl-auto: validate` as a free mapping test
- Testcontainers: disposable, real infrastructure for each test run
- The singleton-container pattern and why it beats one container per class
- Keeping test data isolated when the database is shared
:::

**After this chapter you will be able to** explain when a fake database is acceptable and when it
is dangerous, set up a real PostgreSQL for tests with minimal start-up cost, and design tests that
don't interfere with each other on a shared database.

::: {.callout .recall}
Warm-up

1. Which Spring slice starts JPA and Flyway but no web layer? (section 8.2)
2. What drives the run time of a Spring test suite more than the number of tests? (8.4)
3. What's the difference between a fake and a stub? (4.2)
:::

## 9.1 The in-memory database is a double

For years, the standard advice for testing a JPA repository was: use H2, an in-memory Java database,
because it's fast and needs nothing installed. The lab's history includes exactly that — its second
commit is titled *"Remove the in-memory database; every tier runs on real PostgreSQL"*.

Why remove something fast and convenient? Because an in-memory database is a **fake** (chapter 4):
a lightweight implementation of the real thing. Fakes are great when they behave like the real
thing *in the ways your test cares about*. For databases, they often don't.

![Figure 9.1 The same migration and query against H2 and against PostgreSQL. The fake can fail where production would succeed, or — worse — succeed where production would fail.](images/09-h2-vs-pg.png)

Concrete ways H2 diverges from PostgreSQL:

- **Types.** `jsonb`, arrays, `TIMESTAMP WITH TIME ZONE` precision, `NUMERIC` overflow behaviour.
- **SQL dialect.** `ON CONFLICT DO UPDATE`, window functions, CTE quirks, identifier case.
- **Constraints and locking.** Deferred constraints, `SELECT … FOR UPDATE SKIP LOCKED`, isolation
  levels.
- **Migrations.** A Flyway migration written for PostgreSQL may not run on H2 at all — so teams
  either maintain two sets of migrations or quietly avoid PostgreSQL features.

That last point is the most corrosive. A fake database doesn't just miss bugs; it **constrains
your design** to the intersection of two databases' features. The lab's `QuoteRepositoryTest`
comment says it well: checking the mapping against a different database product "would check very
little, and would quietly forbid the migration from using anything PostgreSQL-specific."

::: {.callout .myth}
Misconception: "In-memory databases make tests faster, so they're better for unit tests"

A repository test is never a unit test — its whole point is the integration between your mapping
and a database engine. Making it fast by swapping the engine removes the thing being tested. Keep
unit tests free of databases entirely (mock or fake the repository at the Java level), and run
repository tests against the real engine.
:::

## 9.2 Flyway owns the schema

The lab's schema lives in a SQL file, applied by Flyway at start-up:

```sql
-- Listing 9.1 V1__create_quotes.sql
CREATE TABLE quotes
(
    id                  UUID                        NOT NULL,
    customer_id         VARCHAR(64)                 NOT NULL,
    product_code        VARCHAR(32)                 NOT NULL,
    amount              NUMERIC(19, 4)              NOT NULL,
    currency            VARCHAR(3)                  NOT NULL,
    term_months         INTEGER                     NOT NULL,
    annual_rate_percent NUMERIC(9, 4)               NOT NULL,
    total               NUMERIC(19, 4)              NOT NULL,
    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version             BIGINT                      NOT NULL DEFAULT 0,
    CONSTRAINT pk_quotes PRIMARY KEY (id)
);
```

And Hibernate is told **not** to create or change it, only to check it:

```yaml
spring.jpa.hibernate.ddl-auto: validate
```

With `validate`, Hibernate compares every `@Entity` field against the actual table at start-up and
refuses to start if they disagree — a missing column, a wrong type. That means *every test that
starts JPA is also a free mapping test*. Combined with a real PostgreSQL, a mismatch between the
migration and the entity can't reach production.

Keep one detail from listing 9.1 in mind for later: `amount` and `total` are `NUMERIC(19, 4)`. The
database will *return* them with four decimal places, whatever scale the application sent. That's
the root of the POST/GET mismatch, and it's also a hard upper limit on how large a total can be —
15 digits before the decimal point.

## 9.3 Testcontainers

**Testcontainers** is a library that starts Docker containers from test code, waits until they're
ready, gives you their connection details, and removes them afterwards. For our purposes it turns
"real PostgreSQL" from a piece of shared infrastructure into a disposable, per-run resource.

Listing 9.2 shows the lab's approach.

```java
// Listing 9.2 One shared PostgreSQL for the whole run (testFixtures/.../PostgresContainer.java)
public final class PostgresContainer {

    private static PostgreSQLContainer container;

    private static synchronized PostgreSQLContainer container() {          // #1
        if (container == null) {
            container = new PostgreSQLContainer("postgres:17-alpine");     // #2
            container.start();
            Runtime.getRuntime().addShutdownHook(new Thread(PostgresContainer::stopQuietly));  // #3
        }
        return container;
    }

    public static String jdbcUrl()  { return container().getJdbcUrl(); }  // #4
    public static String username() { return container().getUsername(); }
    public static String password() { return container().getPassword(); }
}
```

::: {.annotations}
1. **Lazy and synchronised.** Nothing starts until someone asks for a URL, and only one thread can
   start it.
2. The **same major version** as production and as `compose.yaml`. Version drift between test and
   production databases is a classic source of "works in CI".
3. Stopped when the JVM exits. (Testcontainers also runs a helper container, Ryuk, that cleans up
   if the JVM dies abruptly.)
4. Only plain strings leave this class, so consumers don't need Testcontainers on their compile
   classpath — the reason for that will become clear in chapter 11.
:::

And the test that uses it:

```java
// Listing 9.3 Pointing a JPA slice at the container (QuoteRepositoryTest)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)   // #1
class QuoteRepositoryTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {                 // #2
        registry.add("spring.datasource.url", () -> PostgresContainer.jdbcUrl());
        registry.add("spring.datasource.username", () -> PostgresContainer.username());
        registry.add("spring.datasource.password", () -> PostgresContainer.password());
    }

    @Test
    void round_trips_every_column_including_scale_and_offset() {
        Instant createdAt = Instant.parse("2026-01-15T10:30:00.123456Z");    // #3
        Quote quote = Quote.create(UUID.randomUUID(), "cust-1", "WIDGET",
                new BigDecimal("12345.6789"), "USD", 24, new BigDecimal("4.2500"),
                new BigDecimal("13744.1300"), createdAt);

        this.repository.saveAndFlush(quote);                                  // #4
        Quote reloaded = this.repository.findById(quote.getId()).orElseThrow();

        assertThat(reloaded.getAmount()).isEqualByComparingTo("12345.6789");
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
    }
}
```

::: {.annotations}
1. By default `@DataJpaTest` swaps in an embedded database. `Replace.NONE` says "use the one I
   configure".
2. Properties computed at run time — the container's random port isn't known until it starts.
3. A timestamp with *microsecond* precision, chosen because PostgreSQL stores microseconds. A
   nanosecond value would come back truncated and fail the equality check — which is exactly the
   `createdAt` precision issue in the POST/GET mismatch.
4. `saveAndFlush` forces the INSERT now, so the mapping is exercised before the read.
:::

::: {.callout .hood}
Under the hood: why `findById` right after `save` may not hit the database

`@DataJpaTest` runs each test in a transaction, and JPA keeps a first-level cache (the persistence
context) per transaction. `findById` for an entity already in that cache returns the *same Java
object* without a SELECT — so a round-trip test can pass without ever reading the row back. To be
sure the value comes from the database, flush and **clear** the entity manager
(`entityManager.clear()`, via an injected `TestEntityManager`) before reading. The lab's test flushes
but doesn't clear; that's worth fixing.
:::

## 9.4 One container per run, not per class

The Testcontainers documentation shows an easy pattern: annotate a field with `@Container` in each
test class. It works — and starts and stops a container **for every test class**. With 40 classes
at 3–5 seconds each, that's minutes of pure start-up.

The lab uses the **singleton container** pattern instead (figure 9.2): one static holder, started on
first use, shared by every class in the JVM.

![Figure 9.2 The singleton container: started on first use, reused by every test class in the JVM, stopped at shutdown.](images/09-singleton.png)

The trade-off: tests now **share a database**. Two consequences follow.

**Isolation must be designed.** `@DataJpaTest` rolls back each test's transaction, so rows don't
leak. But tests that don't use a rolled-back transaction — a `@SpringBootTest` calling the app over
HTTP, where the app commits its own transactions — will leave rows behind. The strategies:

| Strategy | How | Trade-off |
| --- | --- | --- |
| Transaction rollback | `@DataJpaTest` default, `@Transactional` tests | Only works when the test owns the transaction |
| Unique data per test | Random ids/customer ids; assert only on your own rows | Rows accumulate; queries must be scoped |
| Truncate between tests | `TRUNCATE` in `@BeforeEach` | Serialises tests; slower |
| Fresh schema per class | Separate schema or database per class | Most isolation, most set-up cost |

The lab's broader tests use *unique data*: each asserts only on the quote it just created. Part 3
formalises this with a unique `customerId` per scenario.

**Context sharing interacts with container sharing.** The `@DynamicPropertySource` values are part
of the Spring cache key (chapter 8). Because the container is a singleton, the URL is always the
same, so contexts can still be shared. One container per class would produce a different URL per
class — and a different Spring context per class. The two optimisations reinforce each other.

::: {.callout .mental}
Mental model: expensive things are shared, cheap things are fresh

Containers and Spring contexts are expensive: share them for the whole run. Data and stub
configuration are cheap: make them fresh (or unique) per test. Most test-suite performance problems
come from getting one of those two backwards.
:::

## 9.5 Proving you're on the real thing

`QuoteRepositoryTest` includes two tests that look odd at first:

```java
@Test
void runs_against_a_real_postgres_instance() throws Exception {
    assertThat(PostgresContainer.productName()).isEqualTo("PostgreSQL");
}

@Test
void flyway_created_the_table_rather_than_hibernate_inferring_it() throws Exception {
    assertThat(PostgresContainer.tableExists("quotes")).isTrue();
}
```

They test the *test setup*, over plain JDBC, outside Hibernate. If someone later "simplifies" the
configuration and an embedded database sneaks back in — say, by removing `Replace.NONE` — these
fail loudly instead of the suite silently testing against the wrong engine. It's the same instinct
as the ArchUnit import canary in chapter 7: **guards need guards**.

::: {.callout .tryit}
Try it: watch the fake lie

On a branch, change `QuoteRepositoryTest` to use an embedded database (remove `Replace.NONE` and add
H2 as a test runtime dependency). Which tests fail, and why? Then imagine a V2 migration adding a
`jsonb` column. What would you have to do to keep the tests green on H2? *Needs a JDK; H2 needs no
Docker.*
:::

::: {.callout .quiz}
Check your understanding

1. Why is an in-memory database a test double, and which kind?
2. What does `ddl-auto: validate` give you for free in every JPA test?
3. Why does the round-trip test use a timestamp with exactly six fractional digits?
4. Compare the per-class `@Container` pattern with the singleton pattern: one advantage of each.
5. Name two strategies for data isolation on a shared database, and when each one works.
:::

::: {.callout .summary}
Summary

- An in-memory database is a **fake** that diverges from production in types, dialect, locking and
  migrations — and it constrains your design. Test repositories against the real engine.
- **Flyway owns the schema**; `ddl-auto: validate` makes every JPA start-up a mapping check.
- **Testcontainers** gives each run disposable, real infrastructure. Pin the same version as
  production.
- Use a **singleton container** for the run: it saves minutes and keeps Spring's cache key stable.
- Sharing a database means **designing isolation**: rollback, unique data, truncation, or separate
  schemas.
- Add small tests that **prove the setup** is what you think it is.
:::
