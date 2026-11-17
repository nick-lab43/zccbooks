package za.co.zccbooks;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/organisations/{organisationId}/financial-ledger")
public class FinancialLedgerReportController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public FinancialLedgerReportController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/trial-balance")
    public TrialBalance trialBalance(@PathVariable UUID organisationId,
            @RequestParam UUID scopeUnitId, @RequestParam LocalDate asOf) {
        requireScope(organisationId, scopeUnitId);
        List<TrialBalanceLine> lines = jdbc.query("""
            WITH RECURSIVE report_units(id) AS (
              SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
              UNION ALL
              SELECT child.id FROM organisational_units child
              JOIN report_units parent ON child.parent_id = parent.id
              WHERE child.organisation_id = ?
            ), posted_activity AS (
              SELECT l.account_id, l.currency, SUM(l.debit_amount) AS debit_total,
                     SUM(l.credit_amount) AS credit_total
              FROM finance_journal_lines l
              JOIN finance_journals j ON j.id = l.journal_id
              JOIN report_units ru ON ru.id = j.scope_unit_id
              WHERE j.organisation_id = ? AND j.status = 'POSTED'
                AND j.journal_date <= ?
              GROUP BY l.account_id, l.currency
            )
            SELECT a.id AS account_id, a.account_code, a.account_name, a.account_type,
                   a.currency, COALESCE(p.debit_total, 0) AS debit_total,
                   COALESCE(p.credit_total, 0) AS credit_total,
                   GREATEST(COALESCE(p.debit_total, 0) - COALESCE(p.credit_total, 0), 0) AS balance_debit,
                   GREATEST(COALESCE(p.credit_total, 0) - COALESCE(p.debit_total, 0), 0) AS balance_credit
            FROM finance_accounts a
            LEFT JOIN posted_activity p ON p.account_id = a.id AND p.currency = a.currency
            WHERE a.organisation_id = ?
            ORDER BY a.currency, a.account_code
            """, (rs, n) -> new TrialBalanceLine(
                rs.getObject("account_id", UUID.class).toString(), rs.getString("account_code"),
                rs.getString("account_name"), rs.getString("account_type"), rs.getString("currency"),
                rs.getBigDecimal("debit_total"), rs.getBigDecimal("credit_total"),
                rs.getBigDecimal("balance_debit"), rs.getBigDecimal("balance_credit")),
            scopeUnitId, organisationId, organisationId, organisationId, asOf, organisationId);
        audit.record("FINANCIAL_TRIAL_BALANCE_GENERATED", "FINANCIAL_LEDGER_REPORT", UUID.randomUUID(),
            Map.of("organisationId", organisationId.toString(), "scopeUnitId", scopeUnitId.toString(),
                "asOf", asOf.toString()));
        return new TrialBalance(scopeUnitId.toString(), asOf, lines);
    }

    @GetMapping("/account-statement")
    public AccountStatement accountStatement(@PathVariable UUID organisationId,
            @RequestParam UUID scopeUnitId, @RequestParam UUID accountId,
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        requireScope(organisationId, scopeUnitId);
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("A valid statement date range is required.");
        }
        AccountIdentity account = jdbc.query("""
            SELECT id, account_code, account_name, account_type, currency
            FROM finance_accounts WHERE id = ? AND organisation_id = ?
            """, (rs, n) -> new AccountIdentity(rs.getObject("id", UUID.class).toString(),
                rs.getString("account_code"), rs.getString("account_name"),
                rs.getString("account_type"), rs.getString("currency")), accountId, organisationId)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Financial account was not found in this organisation."));

        Balance opening = jdbc.queryForObject("""
            WITH RECURSIVE report_units(id) AS (
              SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
              UNION ALL
              SELECT child.id FROM organisational_units child
              JOIN report_units parent ON child.parent_id = parent.id
              WHERE child.organisation_id = ?
            )
            SELECT COALESCE(SUM(l.debit_amount), 0) AS debit_total,
                   COALESCE(SUM(l.credit_amount), 0) AS credit_total
            FROM finance_journal_lines l
            JOIN finance_journals j ON j.id = l.journal_id
            JOIN report_units ru ON ru.id = j.scope_unit_id
            WHERE j.organisation_id = ? AND j.status = 'POSTED'
              AND j.journal_date < ? AND l.account_id = ?
            """, (rs, n) -> new Balance(rs.getBigDecimal("debit_total"), rs.getBigDecimal("credit_total")),
            scopeUnitId, organisationId, organisationId, organisationId, from, accountId);

        List<AccountStatementLine> entries = jdbc.query("""
            WITH RECURSIVE report_units(id) AS (
              SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
              UNION ALL
              SELECT child.id FROM organisational_units child
              JOIN report_units parent ON child.parent_id = parent.id
              WHERE child.organisation_id = ?
            )
            SELECT j.id AS journal_id, j.journal_number, j.journal_date, j.description,
                   u.name AS unit_name, l.debit_amount, l.credit_amount, l.description AS line_description
            FROM finance_journal_lines l
            JOIN finance_journals j ON j.id = l.journal_id
            JOIN report_units ru ON ru.id = j.scope_unit_id
            JOIN organisational_units u ON u.id = j.scope_unit_id
            WHERE j.organisation_id = ? AND j.status = 'POSTED'
              AND j.journal_date BETWEEN ? AND ? AND l.account_id = ?
            ORDER BY j.journal_date, j.journal_number, l.id
            """, (rs, n) -> new AccountStatementLine(
                rs.getObject("journal_id", UUID.class).toString(), rs.getString("journal_number"),
                rs.getDate("journal_date").toLocalDate(), rs.getString("description"),
                rs.getString("unit_name"), rs.getBigDecimal("debit_amount"),
                rs.getBigDecimal("credit_amount"), rs.getString("line_description")),
            scopeUnitId, organisationId, organisationId, organisationId, from, to, accountId);

        BigDecimal periodDebit = entries.stream().map(AccountStatementLine::debitAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal periodCredit = entries.stream().map(AccountStatementLine::creditAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal openingNet = opening.debitTotal().subtract(opening.creditTotal());
        BigDecimal closingNet = openingNet.add(periodDebit).subtract(periodCredit);
        Balance closing = new Balance(closingNet.max(BigDecimal.ZERO), closingNet.negate().max(BigDecimal.ZERO));
        audit.record("FINANCIAL_ACCOUNT_STATEMENT_GENERATED", "FINANCIAL_LEDGER_REPORT", UUID.randomUUID(),
            Map.of("organisationId", organisationId.toString(), "scopeUnitId", scopeUnitId.toString(),
                "accountId", accountId.toString(), "from", from.toString(), "to", to.toString()));
        return new AccountStatement(scopeUnitId.toString(), account, from, to,
            new Balance(openingNet.max(BigDecimal.ZERO), openingNet.negate().max(BigDecimal.ZERO)),
            periodDebit, periodCredit, closing, entries);
    }

    private void requireScope(UUID organisationId, UUID scopeUnitId) {
        authorization.requireUnitPermission("FINANCE_JOURNAL_VIEW", scopeUnitId);
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM organisational_units WHERE id = ? AND organisation_id = ?
            """, Integer.class, scopeUnitId, organisationId);
        if (count == null || count == 0) {
            throw new ResourceNotFoundException("Reporting scope was not found in this organisation.");
        }
    }

    public record TrialBalance(String scopeUnitId, LocalDate asOf, List<TrialBalanceLine> lines) {}
    public record TrialBalanceLine(String accountId, String accountCode, String accountName,
        String accountType, String currency, BigDecimal totalDebits, BigDecimal totalCredits,
        BigDecimal balanceDebit, BigDecimal balanceCredit) {}
    public record AccountIdentity(String id, String accountCode, String accountName,
        String accountType, String currency) {}
    public record Balance(BigDecimal debit, BigDecimal credit) {}
    public record AccountStatement(String scopeUnitId, AccountIdentity account, LocalDate from,
        LocalDate to, Balance openingBalance, BigDecimal periodDebits, BigDecimal periodCredits,
        Balance closingBalance, List<AccountStatementLine> entries) {}
    public record AccountStatementLine(String journalId, String journalNumber, LocalDate journalDate,
        String journalDescription, String unitName, BigDecimal debitAmount,
        BigDecimal creditAmount, String lineDescription) {}
}
