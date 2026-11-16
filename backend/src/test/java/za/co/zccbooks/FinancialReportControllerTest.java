package za.co.zccbooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinancialReportControllerTest {

    @Test
    void contributionTotalsRemainSeparatedByCurrency() {
        List<FinancialReportController.ContributionGroup> groups = List.of(
            new FinancialReportController.ContributionGroup("cat-1", "Tithing", "stream-1", "Main",
                "ZAR", 2, new BigDecimal("150.00")),
            new FinancialReportController.ContributionGroup("cat-2", "Building", "stream-1", "Main",
                "ZAR", 1, new BigDecimal("50.00")),
            new FinancialReportController.ContributionGroup("cat-3", "Missions", "stream-2", "Miscellaneous",
                "USD", 1, new BigDecimal("25.00"))
        );

        List<FinancialReportController.CurrencyTotal> totals =
            FinancialReportController.totalsByCurrency(groups);

        assertEquals(2, totals.size());
        assertEquals("USD", totals.get(0).currency());
        assertEquals(new BigDecimal("25.00"), totals.get(0).totalAmount());
        assertEquals("ZAR", totals.get(1).currency());
        assertEquals(new BigDecimal("200.00"), totals.get(1).totalAmount());
    }

    @Test
    void emptyContributionReportHasNoCurrencyTotals() {
        assertEquals(List.of(), FinancialReportController.totalsByCurrency(List.of()));
    }
}
