@known-bug
Feature: Defects the black-box suite exposes

  Each scenario here describes a real defect. The previous in-process suite could not see any of
  them, because it asserted through the application's own response type - a domain object that has
  already lost the distinction being tested.

  They are excluded from the build by default (`not @known-bug`) so the suite does not start life
  red, and they are the reason the suite is worth having: it found them on the first run. Fix each
  in a follow-up change and delete its tag.

  Scenario: Reading a quote back changes the scale of the numbers
    Given the rate service quotes 4.25% for product "WIDGET" in "USD"
    When I request a quote for 10000.00 USD over 12 months of product "WIDGET"
    Then the response status is 201
    And the quote amount is 10000.00
    When I fetch that quote
    Then the response status is 200
    # The column is NUMERIC(19,4), so the stored value comes back as 10000.0000.
    And the quote amount is 10000.0000

  Scenario: Reading a quote back changes the precision of the timestamp
    Given the rate service quotes 4.25% for product "WIDGET" in "USD"
    When I request a quote for 10000.00 USD over 12 months of product "WIDGET"
    And I fetch that quote
    Then the response status is 200
    And the created timestamp has microsecond precision

  Scenario: An amount near the validation limit is a server error rather than a rejection
    Given the rate service quotes 4.25% for product "WIDGET" in "USD"
    When I request a quote for 999999999999999.9999 USD over 12 months of product "WIDGET"
    Then the response status is 500

  Scenario: An unknown product is reported as an unavailable service
    Given the rate service answers 404 for product "NOSUCH"
    When I request a quote for 1000.00 USD over 12 months of product "NOSUCH"
    # A 404 means "this rate does not exist", which is the caller's question answered - not an
    # outage. Reporting it as 503 invites the caller to retry something that will never work.
    Then the response status is 422
