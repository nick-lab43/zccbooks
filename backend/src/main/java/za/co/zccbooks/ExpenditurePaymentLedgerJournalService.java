package za.co.zccbooks;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates one balanced draft journal for each expenditure payment.
 * Approval alone is not an accounting event; only the PAID transition invokes this service.
 */
@Service
public class ExpenditurePaymentLedgerJournalService {
    private final JdbcTemplate jdbc;
    private final AdminAuditService audit;

    public ExpenditurePaymentLedgerJournalService(JdbcTemplate jdbc, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public UUID createDraftIfMapped(UUID expenditureId) {
        ExpenditureSource source = jdbc.query("""
            SELECT id, organisation_id, scope_unit_id, category_id, expense_date,
                   amount, currency, payee, description, status, paid_by, paid_at, payment_reference
            FROM expenditures
            WHERE id = ?
            FOR UPDATE
            """, (rs, n) -> new ExpenditureSource(
                rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class),
                rs.getObject("scope_unit_id", UUID.class),
                rs.getObject("category_id", UUID.class),
                rs.getObject("expense_date", LocalDate.class),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("payee"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getObject("paid_by", UUID.class),
                rs.getTimestamp("paid_at") == null ? null
                    : rs.getTimestamp("paid_at").toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                rs.getString("payment_reference")), expenditureId)
            .stream().findFirst().orElseThrow(() ->
                new ResourceNotFoundException("Expenditure record not found."));

        if (!"PAID".equals(source.status()) || source.paidBy() == null || source.paidAt() == null) {
            return null;
        }

        UUID existingJournal = jdbc.query("""
            SELECT id FROM finance_journals
            WHERE organisation_id = ? AND source_type = 'EXPENDITURE_PAYMENT' AND source_id = ?
            """, (rs, n) -> rs.getObject("id", UUID.class),
            source.organisationId(), expenditureId).stream().findFirst().orElse(null);
        if (existingJournal != null) return existingJournal;

        Mapping mapping = jdbc.query("""
            SELECT m.debit_account_id, m.credit_account_id
            FROM finance_expenditure_account_mappings m
            JOIN finance_accounts debit_account
              ON debit_account.id = m.debit_account_id
             AND debit_account.organisation_id = m.organisation_id
             AND debit_account.currency = m.currency
             AND debit_account.account_type = 'EXPENSE'
             AND debit_account.active = TRUE
            JOIN finance_accounts credit_account
              ON credit_account.id = m.credit_account_id
             AND credit_account.organisation_id = m.organisation_id
             AND credit_account.currency = m.currency
             AND credit_account.account_type = 'ASSET'
             AND credit_account.active = TRUE
            WHERE m.organisation_id = ? AND m.category_id = ? AND m.currency = ?
            """, (rs, n) -> new Mapping(
                rs.getObject("debit_account_id", UUID.class),
                rs.getObject("credit_account_id", UUID.class)),
            source.organisationId(), source.categoryId(), source.currency())
            .stream().findFirst().orElse(null);

        if (mapping == null) {
            audit.record("EXPENDITURE_LEDGER_MAPPING_MISSING", "EXPENDITURE",
                expenditureId, Map.of(
                    "organisationId", source.organisationId().toString(),
                    "scopeUnitId", source.scopeUnitId().toString(),
                    "categoryId", source.categoryId().toString(),
                    "amount", source.amount().toPlainString(),
                    "currency", source.currency(),
                    "paymentReference", source.paymentReference() == null ? "" : source.paymentReference()));
            return null;
        }

        UUID journalId = UUID.randomUUID();
        String journalNumber = "AUTO-EXP-" + expenditureId.toString().replace("-", "").toUpperCase();
        String description = "Expenditure payment to " + source.payee()
            + " (" + source.paymentReference() + ")";

        jdbc.update("""
            INSERT INTO finance_journals
                (id, organisation_id, scope_unit_id, journal_number, journal_date, currency,
                 description, source_type, source_id, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'EXPENDITURE_PAYMENT', ?, ?)
            """, journalId, source.organisationId(), source.scopeUnitId(), journalNumber,
            source.paidAt(), source.currency(), description, expenditureId, source.paidBy());

        jdbc.update("""
            INSERT INTO finance_journal_lines
                (id, journal_id, account_id, organisation_id, currency,
                 debit_amount, credit_amount, description)
            VALUES (?, ?, ?, ?, ?, ?, 0, ?)
            """, UUID.randomUUID(), journalId, mapping.debitAccountId(), source.organisationId(),
            source.currency(), source.amount(), "Recognise paid expenditure");

        jdbc.update("""
            INSERT INTO finance_journal_lines
                (id, journal_id, account_id, organisation_id, currency,
                 debit_amount, credit_amount, description)
            VALUES (?, ?, ?, ?, ?, 0, ?, ?)
            """, UUID.randomUUID(), journalId, mapping.creditAccountId(), source.organisationId(),
            source.currency(), source.amount(), "Reduce cash or bank asset");

        audit.record("EXPENDITURE_DRAFT_JOURNAL_CREATED", "FINANCE_JOURNAL", journalId,
            Map.of(
                "organisationId", source.organisationId().toString(),
                "scopeUnitId", source.scopeUnitId().toString(),
                "expenditureId", expenditureId.toString(),
                "paymentReference", source.paymentReference() == null ? "" : source.paymentReference(),
                "amount", source.amount().toPlainString(),
                "currency", source.currency(),
                "journalNumber", journalNumber));
        return journalId;
    }

    /** Creates a draft journal reversing a posted expenditure payment journal. */
    @Transactional
    public UUID createReversalDraft(UUID expenditureId, UUID reversalId, UUID actorId, String reason) {
        UUID existing = jdbc.query("""
            SELECT id FROM finance_journals
            WHERE source_type = 'EXPENDITURE_PAYMENT_REVERSAL' AND source_id = ?
            """, (rs, n) -> rs.getObject("id", UUID.class), reversalId)
            .stream().findFirst().orElse(null);
        if (existing != null) return existing;

        OriginalJournal original = jdbc.query("""
            SELECT id, organisation_id, scope_unit_id, journal_number, currency, status
            FROM finance_journals
            WHERE source_type = 'EXPENDITURE_PAYMENT' AND source_id = ?
            FOR UPDATE
            """, (rs, n) -> new OriginalJournal(
                rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                rs.getObject("scope_unit_id", UUID.class), rs.getString("journal_number"),
                rs.getString("currency"), rs.getString("status")), expenditureId)
            .stream().findFirst().orElse(null);

        // A payment made before ledger mapping existed can still be reversed operationally.
        if (original == null) return null;
        if (!"POSTED".equals(original.status())) {
            throw new ConflictException("The expenditure payment journal must be posted before the payment can be reversed. Complete or reject the original journal workflow first.");
        }

        List<JournalLine> originalLines = jdbc.query("""
            SELECT account_id, debit_amount, credit_amount, description
            FROM finance_journal_lines
            WHERE journal_id = ?
            ORDER BY created_at, id
            """, (rs, n) -> new JournalLine(
                rs.getObject("account_id", UUID.class), rs.getBigDecimal("debit_amount"),
                rs.getBigDecimal("credit_amount"), rs.getString("description")), original.id());
        if (originalLines.size() < 2) {
            throw new ConflictException("The posted expenditure journal has no complete line set and cannot be reversed automatically.");
        }
        BigDecimal debits = originalLines.stream().map(JournalLine::debitAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = originalLines.stream().map(JournalLine::creditAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debits.compareTo(credits) != 0 || debits.signum() <= 0) {
            throw new ConflictException("The original expenditure journal is not balanced and cannot be reversed automatically.");
        }

        UUID journalId = UUID.randomUUID();
        String number = "AUTO-EXP-REV-" + reversalId.toString().replace("-", "").toUpperCase();
        jdbc.update("""
            INSERT INTO finance_journals
                (id, organisation_id, scope_unit_id, journal_number, journal_date, currency,
                 description, source_type, source_id, created_by)
            VALUES (?, ?, ?, ?, CURRENT_DATE, ?, ?, 'EXPENDITURE_PAYMENT_REVERSAL', ?, ?)
            """, journalId, original.organisationId(), original.scopeUnitId(), number,
            original.currency(), "Reversal of " + original.journalNumber() + ": " + reason,
            reversalId, actorId);

        for (JournalLine line : originalLines) {
            jdbc.update("""
                INSERT INTO finance_journal_lines
                    (id, journal_id, account_id, organisation_id, currency,
                     debit_amount, credit_amount, description)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), journalId, line.accountId(), original.organisationId(),
                original.currency(), line.creditAmount(), line.debitAmount(),
                "Reversal of " + (line.description() == null ? "original journal line" : line.description()));
        }

        audit.record("EXPENDITURE_PAYMENT_REVERSAL_DRAFT_JOURNAL_CREATED", "FINANCE_JOURNAL", journalId,
            Map.of("organisationId", original.organisationId().toString(),
                "scopeUnitId", original.scopeUnitId().toString(),
                "expenditureId", expenditureId.toString(), "reversalId", reversalId.toString(),
                "originalJournalId", original.id().toString(), "originalJournalNumber", original.journalNumber(),
                "journalNumber", number));
        return journalId;
    }

    @Transactional
    public int retryForMapping(UUID organisationId, UUID categoryId, String currency) {
        List<UUID> eligible = jdbc.query("""
            SELECT e.id
            FROM expenditures e
            WHERE e.organisation_id = ? AND e.category_id = ?
              AND e.currency = ? AND e.status = 'PAID'
              AND NOT EXISTS (
                  SELECT 1 FROM finance_journals j
                  WHERE j.organisation_id = e.organisation_id
                    AND j.source_type = 'EXPENDITURE_PAYMENT'
                    AND j.source_id = e.id
              )
            ORDER BY e.paid_at, e.id
            """, (rs, n) -> rs.getObject("id", UUID.class),
            organisationId, categoryId, currency);
        int created = 0;
        for (UUID expenditureId : eligible) {
            if (createDraftIfMapped(expenditureId) != null) created++;
        }
        return created;
    }

    private record ExpenditureSource(UUID id, UUID organisationId, UUID scopeUnitId,
        UUID categoryId, LocalDate expenseDate, BigDecimal amount, String currency,
        String payee, String description, String status, UUID paidBy, LocalDate paidAt,
        String paymentReference) {}
    private record Mapping(UUID debitAccountId, UUID creditAccountId) {}
    private record OriginalJournal(UUID id, UUID organisationId, UUID scopeUnitId,
        String journalNumber, String currency, String status) {}
    private record JournalLine(UUID accountId, BigDecimal debitAmount,
        BigDecimal creditAmount, String description) {}
}
