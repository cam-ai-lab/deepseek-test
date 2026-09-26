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
    And the fetched quote belongs to my customer
    # Totals and amounts on GET are not asserted here: their scale differs from the POST response,
    # which is a known bug with its own scenario in known_bugs.feature. One reason to fail each.
