# Appendix D — Cheat sheets

## D.1 The four axes

| Axis | Question | Values |
| --- | --- | --- |
| Scope | How much really runs? | unit · integration · system · end to end |
| Visibility | What does the test share with the inside? | white · grey · black |
| Purpose | Which risk does it guard? | functional · regression · contract · performance · acceptance · security · architecture |
| Environment & phase | Where and when? | in-process · container · ephemeral · staging · production; laptop · PR · nightly · post-deploy |

**Design order:** purpose → visibility → smallest scope → cheapest environment.

**Black-box check:** would the test survive a reimplementation of the system in another language?

## D.2 Commonly confused terms

| Pair | Real difference | Axis |
| --- | --- | --- |
| Integration vs system | A few real parts vs the whole deployable | Scope |
| System vs end to end | Stubbed neighbours vs real ones | Scope |
| System vs black-box | Size vs knowledge | Scope vs visibility |
| System vs ephemeral | What is tested vs where it runs | Scope vs environment |
| Regression vs unit | Why vs how big | Purpose vs scope |
| Acceptance vs E2E | Who defines correct vs how much runs | Purpose vs scope |
| Contract vs integration | Checks an agreement; can run without the other party | Purpose vs scope |

## D.3 Test doubles

| Kind | Does | Use for |
| --- | --- | --- |
| Dummy | Nothing | Filling a parameter |
| Stub | Returns canned answers | Supplying inputs |
| Spy | Stub + records calls | Inspecting calls afterwards |
| Mock | Verifies interactions | Edges where the call *is* the behaviour |
| Fake | Works, cheaply | Realistic behaviour shared by many tests |

| Level | Replace | Real code that runs |
| --- | --- | --- |
| 1 · object | Java interface | Logic |
| 2 · network | Remote server (WireMock) | + adapter, HTTP, JSON, timeouts |
| 3 · environment | Nothing in the app | Everything as shipped |

## D.4 Which test should I write?

1. Pure logic? → **unit test** (tables, properties).
2. Risk is how our code talks to one technology (SQL, HTTP, JSON)? → **integration/slice test** with
   the real technology.
3. Risk is the agreement with another team? → **contract test**.
4. Risk is the assembled, shipped product (wiring, config, wire format)? → **black-box scenario**.
5. Speed or capacity? → **performance test** (nightly).
6. None of these? → rethink what could actually go wrong.

## D.5 Spring test slices

| Annotation | Loads | For |
| --- | --- | --- |
| `@WebMvcTest(X)` | Controller, advice, Jackson, validation | Routing, binding, errors |
| `@DataJpaTest` | JPA, DataSource, Flyway | Mapping, queries, migrations |
| `@JsonTest` | Jackson | Serialisation format |
| `@RestClientTest` | REST client + mock server | HTTP clients |
| `@SpringBootTest` | Everything | Whole-app wiring |

**Context cache splitters:** `@MockitoBean`, `@ActiveProfiles`, `@TestPropertySource`, separate
`@DynamicPropertySource` methods, `@DirtiesContext`, nested `@TestConfiguration`.

## D.6 Environments and phases

| Rung | Sees | Typical phase |
| --- | --- | --- |
| In-process | Logic, framework wiring | Every build |
| Containers | Real tech, the image, shipped config | Every PR |
| Ephemeral env | The deployment itself | Every PR (if used) |
| Staging | Real neighbours, data volume | Pre-release |
| Production | Reality | After deploy (smoke), continuously (synthetic) |

## D.7 Merge-gate rule

A check blocks merges only if it is **fast**, **deterministic**, and **about this change**.

## D.8 Flaky-test policy

Detect → quarantine within a day → assign an owner → fix or delete by a deadline → measure the
quarantine size.

## D.9 Gherkin quick reference

```gherkin
@tag
Feature: <capability>

  Background:
    Given <shared context>

  Scenario: <one example>
    Given <context>
    When <the one action>
    Then <observable outcome>
    And <another outcome>

  Scenario Outline: <template>
    When I do <thing>
    Then I see <result>
    Examples:
      | thing | result |
      | a     | b      |
```

Built-in parameter types: `{int}` `{long}` `{float}` `{double}` `{bigdecimal}` `{biginteger}`
`{word}` `{string}` `{}`.

## D.10 Money checklist

- Build `BigDecimal` from strings.
- Pick the rounding mode deliberately (`HALF_UP` vs `HALF_EVEN`) and pin it with a half-cent test.
- Decide whether scale is part of the contract; use `isEqualTo` if it is, `isEqualByComparingTo` if not.
- Check the database column's precision against the largest valid value.
