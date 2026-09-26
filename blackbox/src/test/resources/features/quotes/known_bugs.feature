@known-bug
Feature: Defects the black-box suite exposes

  Each scenario states the CORRECT behaviour and fails today. They are excluded from the build by
  default (`not @known-bug`) so the suite does not start life red. Run them with
  `./gradlew :blackbox:test -Ptags=@known-bug`. When a defect is fixed its scenario passes: delete
  the tag and it becomes an ordinary regression test.

  Never rewrite one of these to assert the buggy behaviour. That would turn the scenario into a
  tripwire that protects the bug and goes red the day someone fixes it.

  The previous in-process suite could not see any of these: it asserted through the application's
  own response type, which has already lost the distinctions being tested.

  Background:
    Given the rate service quotes 4.25% for product "WIDGET" in "USD"

  Scenario: A created quote reads back exactly as it was returned
    # Today POST echoes the request's scale (10000.00) while GET returns the stored NUMERIC(19,4)
    # scale (10000.0000), and createdAt can lose precision (nanoseconds vs microseconds).
    When I request a quote for 10000.00 USD over 12 months of product "WIDGET"
    And I fetch that quote
    Then the response status is 200
    And the fetched JSON equals the created JSON

  Scenario: The largest valid amount never causes a server error
    # Validation allows 15 integer digits; the total needs 16; the total column holds 15.
    When I request a quote for 999999999999999.9999 USD over 12 months of product "WIDGET"
    Then the response status is not a server error

  Scenario: An unknown product is the caller's problem, not an outage
    # A 404 from the rate service means "no such product". Reporting it as 503 invites the caller to
    # retry something that can never succeed. The exact 4xx is a product decision; 503 is wrong.
    Given the rate service answers 404 for product "NOSUCH"
    When I request a quote for 1000.00 USD over 12 months of product "NOSUCH"
    Then the response status is a client error
