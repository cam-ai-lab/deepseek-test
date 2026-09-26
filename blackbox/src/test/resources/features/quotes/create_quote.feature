Feature: Create a quote

  The happy path, asserted only through HTTP and the database. Nothing here needs to know how the
  service is built.

  Background:
    Given the rate service quotes 4.25% for product "WIDGET" in "USD"

  Scenario: A valid command is priced with the upstream rate and persisted
    When I request a quote for 10000.00 USD over 12 months of product "WIDGET"
    Then the response status is 201
    And the response has a Location header pointing at the new quote
    And the quote total is "10425.00"
    And 1 quote is stored for my customer
    And the stored quote has total "10425.0000" and rate "4.2500"
    And the stored quote is for 12 months

  Scenario: The same command expressed as a table
    When I request a quote:
      | productCode | amount   | currency | termMonths |
      | WIDGET      | 10000.00 | USD      | 12         |
    Then the response status is 201
    And the quote total is "10425.00"

  Scenario: The rate really is fetched over the network
    When I request a quote for 1000.00 USD over 6 months of product "WIDGET"
    Then the response status is 201
    And the quote rate is "4.25"
    And the rate service was asked for product "WIDGET" in "USD"

  Scenario: Two commands create two separate quotes
    When I request a quote for 50.00 USD over 1 month of product "WIDGET"
    And I request a quote for 50.00 USD over 1 month of product "WIDGET"
    Then the response status is 201
    And 2 quotes are stored for my customer
