Feature: Read a quote

  Background:
    Given the rate service quotes 4.25% for product "WIDGET" in "USD"

  Scenario: A quote that was never created is not found
    When I fetch a quote that does not exist
    Then the response is a problem of type "urn:problem:quote-not-found" with status 404

  Scenario: A created quote can be read back
    When I request a quote for 25000.00 USD over 24 months of product "WIDGET"
    Then the response status is 201
    When I fetch that quote
    Then the response status is 200
    And the quote amount is 25000.00
    And the quote total is 27125.00
