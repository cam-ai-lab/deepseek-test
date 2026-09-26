Feature: The HTTP contract

  This suite compares the raw bytes the service sends. The field set is pinned, and so is the scale
  of every number - `10425.00` and `10425.0` are different bytes and a strict downstream parser will
  notice. A change here is a breaking change for consumers, not an implementation detail.

  Scenario: The documented quote shape is exactly what comes back
    Given the rate service quotes 4.25% for product "WIDGET" in "USD"
    When I request a quote for 10000.00 USD over 12 months of product "WIDGET"
    Then the response status is 201
    And the response has exactly the documented fields
    And the quote amount is 10000.00
    And the quote rate is 4.25
    And the quote total is 10425.00

  Scenario: An unknown quote reports a problem document, not an empty body
    When I fetch a quote that does not exist
    Then the response is a problem of type "urn:problem:quote-not-found" with status 404

  Scenario: An unreachable rate service reports a problem document
    Given the rate service answers 503 for product "WIDGET"
    When I request a quote for 10000.00 USD over 12 months of product "WIDGET"
    Then the response is a problem of type "urn:problem:rate-unavailable" with status 503
