Feature: The upstream rate service misbehaves

  Everything here is about what the service does when the thing it depends on lets it down. The
  answers matter: a dependency being unavailable is not the caller's fault, so it must not look like
  a server error, and it must not leave half-written data behind.

  Scenario Outline: An upstream failure is reported as 503 and nothing is saved
    Given the rate service answers <status> for product "WIDGET"
    When I request a quote for 1000.00 USD over 12 months of product "WIDGET"
    Then the response is a problem of type "urn:problem:rate-unavailable" with status 503
    And the response carries no quote
    And no quote is stored for my customer

    Examples:
      | status |
      | 500    |
      | 503    |

  Scenario: A rate service that never answers is reported as 503
    Given the rate service takes 5 seconds to answer for product "WIDGET"
    When I request a quote for 1000.00 USD over 12 months of product "WIDGET"
    Then the response status is 503
    And no quote is stored for my customer

  Scenario Outline: An invalid command is rejected without troubling the upstream
    When I request a quote:
      | productCode | amount   | currency | termMonths |
      | WIDGET      | <amount> | USD      | <term>     |
    Then the response status is 400
    And the rate service was never asked for product "WIDGET"

    Examples:
      | amount | term |
      | 0.00   | 12   |
      | -5.00  | 12   |
      | 100.00 | 0    |
      | 100.00 | 601  |
