# 5 Unit tests that earn their keep

::: {.callout .covers}
This chapter covers

- What makes code *unit-testable*, and why the lab's calculator is ideal
- Equivalence partitioning and boundary-value analysis
- Parameterised tests with JUnit 5
- Money, `BigDecimal`, scale and rounding modes
- Testing validation rules without starting Spring
- A first taste of property-based thinking
:::

**After this chapter you will be able to** derive a small, sufficient set of test cases for any
function from its input space, express them as a parameterised test, and avoid the classic money
bugs.

::: {.callout .recall}
Warm-up

1. What's the difference between a stub and a fake? (section 4.2)
2. When should you prefer state verification over interaction verification? (4.3)
3. What's the four-part address of `QuoteCalculatorTest`? (2.3)
:::

## 5.1 The cheapest test in the building

Look at the calculator again.

```java
// Listing 5.1 QuoteCalculator (src/main/.../quote/QuoteCalculator.java)
@Component
class QuoteCalculator {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    BigDecimal totalFor(BigDecimal amount, BigDecimal annualPercentage, int termMonths) {
        BigDecimal interest = amount.multiply(annualPercentage)
                .multiply(BigDecimal.valueOf(termMonths))
                .divide(MONTHS_PER_YEAR.multiply(PERCENT), MathContext.DECIMAL64);   // #1
        return amount.add(interest).setScale(MONEY_SCALE, RoundingMode.HALF_UP);   // #2
    }
}
```

::: {.annotations}
1. Divide with a `MathContext` — 16 significant digits — so a non-terminating division like
   1/3 doesn't throw `ArithmeticException`.
2. Round the *final* total to cents, half-up.
:::

No I/O, no clock, no Spring (the `@Component` annotation is just a label; the class works fine with
`new`). The same inputs always produce the same output. This is the ideal unit under test: **a
pure function**. Tests of pure functions are fast, deterministic and never flaky, so you can afford
to be thorough. The design lesson is to push as much logic as possible into pure functions and keep
the I/O at the edges — sometimes called "functional core, imperative shell".

::: {.callout .mental}
Mental model: functional core, imperative shell

Put decisions in pure code (easy, exhaustive unit tests) and put side effects in a thin shell
around it (fewer, broader integration tests). The quote service follows this shape: `QuoteCalculator`
is the core; `QuoteService` is a thin shell that fetches, calls the core, and saves.
:::

## 5.2 How many tests? Partition the input space

You can't test every input. `totalFor` takes two `BigDecimal`s and an `int` — effectively infinite
combinations. The classic technique for picking a small, sufficient set is **equivalence
partitioning**: divide each input's possible values into groups (partitions) that the code should
treat *the same way*, then test one representative from each.

For `termMonths` as the *API* sees it (validated by `@Min(1) @Max(600)` on `QuoteRequest`):

![Figure 5.1 termMonths has three partitions. Bugs cluster at the edges between them.](images/05-partitions.png)

Three partitions: too small (≤ 0), valid (1–600), too large (≥ 601). One representative each: say
−5, 12 and 1000.

Then add **boundary-value analysis**: bugs love edges — off-by-one errors, `<` versus `<=`. So test
the values *on and next to* each boundary: 0 and 1, 600 and 601.

For the calculator's arithmetic, the partitions are about *behaviour*, not validation:

| Input | Partitions worth separate cases |
| --- | --- |
| `annualPercentage` | zero (no interest), typical (1–20), high (≥ 100), fractional (4.25, 0.99) |
| `termMonths` | zero (defined: no interest), one, a full year, multi-year |
| `amount` | tiny (0.01), typical, many decimal places (123.4567), very large |
| Rounding | result exactly on a cent, result exactly on a half cent, just below/above a half cent |

The lab's `QuoteCalculatorTest` covers most of these rows. Listing 5.2 shows how.

## 5.3 Parameterised tests

Writing one `@Test` method per case would bury the *data* in boilerplate. JUnit 5's
`@ParameterizedTest` separates the cases from the logic.

```java
// Listing 5.2 A table of cases (QuoteCalculatorTest)
@ParameterizedTest(name = "{0} at {1}% over {2} months = {3}")      // #1
@CsvSource({
        "10000.00, 5.00,   12, 10500.00",
        "10000.00, 4.25,   24, 10850.00",
        " 1500.55, 3.75,   18,  1584.96",
        "  123.4567, 1.50,  7,   124.54",                            // #2
        "    0.01, 100.00,  1,     0.01",                            // #3
})
void scales_with_amount_rate_and_term(String amount, String annualPercentage, int termMonths,
        String expectedTotal) {
    BigDecimal total = calculator.totalFor(new BigDecimal(amount), new BigDecimal(annualPercentage),
            termMonths);

    assertThat(total).isEqualTo(new BigDecimal(expectedTotal));      // #4
}
```

::: {.annotations}
1. The `name` pattern makes every case readable in the report: *"123.4567 at 1.50% over 7 months =
   124.54"*.
2. Four decimal places in, two out — exercises rounding of the input's extra precision.
3. The tiniest amount at an extreme rate: interest of 0.000833… must round away.
4. `isEqualTo`, **not** `isEqualByComparingTo`: here scale *is* part of the contract ("always two
   decimal places"), so we check it. Compare section 3.5.
:::

Why strings instead of `double` columns? Because `new BigDecimal(1500.55)` (a double) is actually
`1500.550000000000068212102632969617843627929688`. Always build money from strings.

Other JUnit 5 sources worth knowing: `@ValueSource` (one argument), `@EnumSource`, `@MethodSource`
(cases built in Java — useful when inputs are objects), and `@CsvFileSource` (cases in a file,
handy when a business analyst owns the table).

::: {.callout .myth}
Misconception: "Parameterised tests are just for saving typing"

They change how you *think* about a test: from "a scenario" to "a rule plus a table of examples".
Reviewers can scan the table for missing partitions ("where's the zero-rate case?") far more easily
than they can scan twenty separate methods.
:::

## 5.4 Money is where unit tests pay for themselves

Financial arithmetic has more traps per line than almost any other code. The lab's tests pin down
three of them.

**Rounding mode.** `HALF_UP` rounds 0.005 to 0.01. `HALF_EVEN` ("banker's rounding", Java's
`MathContext.DECIMAL64` default) rounds 0.005 to 0.00, because 0 is even. Which one is right is a
*business* decision, and the test documents it:

```java
// Listing 5.3 Pinning the rounding rule (QuoteCalculatorTest)
@Test
void rounds_half_up_so_a_half_cent_goes_to_the_customer() {
    // 10.00 * 0.6% for 1 month = 0.005 interest -> exactly a half cent.
    // HALF_UP gives 10.01; banker's rounding would wrongly give 10.00.
    BigDecimal total = calculator.totalFor(new BigDecimal("10.00"), new BigDecimal("0.6"), 1);

    assertThat(total).isEqualTo(new BigDecimal("10.01"));
}
```

Notice how carefully that input was chosen: it lands *exactly* on a half cent, the one place where
the two rounding modes disagree. A random input would almost never find this. That's boundary-value
analysis again — the boundary here is between "rounds down" and "rounds up".

**Scale.** `always_returns_two_decimal_places` checks that even `100 × 0% × 12` comes back as
`100.00`, not `100`. A client that formats the JSON number directly would show them differently.

**Precision.** `MathContext.DECIMAL64` keeps 16 significant digits. The API accepts amounts with 15
integer digits and 4 decimals — 19 significant digits. At the very top of the range, then, the
intermediate interest carries fewer digits than the inputs. Whether that ever changes a cent for a
realistic input is a question a unit test can answer in milliseconds — and it's the kind of
question nobody asks until something goes wrong. We'll come back to the top of the range in chapter
16, where it turns out there's a bigger problem waiting there.

## 5.5 Testing validation without Spring

The input rules live as annotations on `QuoteRequest`:

```java
public record QuoteRequest(
        @NotBlank @Size(max = 64) String customerId,
        @NotBlank @Size(max = 32) String productCode,
        @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @Min(1) @Max(600) int termMonths) {
}
```

In the lab these rules are exercised only in `QuoteControllerTest`, a Spring slice that needs a
web context — and it checks one invalid command. But the rules themselves are pure: a Jakarta Bean
Validation `Validator` can check them in a plain unit test, in microseconds.

```java
// Listing 5.4 Boundary tests for validation, no Spring needed
class QuoteRequestValidationTest {

    private static final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();           // #1

    @ParameterizedTest(name = "termMonths={0} valid={1}")
    @CsvSource({ "0,false", "1,true", "600,true", "601,false" })               // #2
    void term_must_be_between_1_and_600_months(int termMonths, boolean valid) {
        QuoteRequest request = QuoteTestData.request("100.00", termMonths);

        assertThat(validator.validate(request).isEmpty()).isEqualTo(valid);
    }
}
```

::: {.annotations}
1. The validation engine (Hibernate Validator) is already on the unit tier's classpath through
   `spring-boot-starter-validation`; no Spring context involved.
2. The four boundary values from figure 5.1.
:::

This split — rules checked exhaustively at unit level, the *wiring* ("does the controller actually
apply `@Valid`?") checked once at slice level — is a pattern you'll see again and again: **test the
logic where it's cheapest, test the wiring once where it's real.**

## 5.6 Thinking in properties

Example-based tests check specific points: "10,000 at 5% for 12 months is 10,500". **Property-based
tests** check rules that hold for *every* input: generate hundreds of random inputs, and check an
invariant each time. For our calculator:

- *For any non-negative rate and term, the total is never less than the amount.*
- *Doubling the term doubles the interest* (within a cent of rounding).
- *The result always has scale 2.*

In Java, the jqwik library does this; its `@Property` works much like `@ParameterizedTest`, but the
framework generates the inputs and, when one fails, **shrinks** it to the smallest failing example.
We'll go deeper in chapter 16. For now, notice that thinking in properties is useful even without a
library: writing down "what's always true?" is a great way to discover partitions you forgot.

::: {.callout .tryit}
Try it: extend the calculator table

Add rows to `scales_with_amount_rate_and_term` for partitions the table doesn't cover yet: a
multi-year term at a fractional rate, and an input that lands *just below* a half cent (so it must
round down). Work out the expected values by hand first — if you use the code to compute them, the
test will only prove the code agrees with itself. *Needs a JDK: `./gradlew test`.*
:::

::: {.callout .quiz}
Check your understanding

1. What are the partitions and boundary values for `customerId`, given `@NotBlank @Size(max = 64)`?
2. Why are the `@CsvSource` values written as strings rather than numbers?
3. Why does the rounding test use exactly `10.00 × 0.6% × 1 month`?
4. The calculator test uses `isEqualTo` but the service test uses `isEqualByComparingTo`. Is that
   inconsistent? Explain.
5. Why is listing 5.4 a unit test, while `QuoteControllerTest`'s validation test is an integration
   test, even though both check the same annotations?
:::

::: {.callout .summary}
Summary

- **Pure functions** are the cheapest code to test; push logic into them ("functional core,
  imperative shell").
- Pick cases with **equivalence partitioning** (one per group) and **boundary-value analysis** (on
  and next to each edge).
- **Parameterised tests** turn a behaviour into a rule plus a readable table of examples.
- Money needs deliberate choices for **rounding mode**, **scale** and **precision** — and tests that
  pin each one with carefully chosen inputs. Always build `BigDecimal` from strings.
- **Validation rules** can be unit-tested with a plain `Validator`; test the wiring once at slice
  level.
- **Properties** ("always true for any input") complement examples and reveal forgotten partitions.
:::
