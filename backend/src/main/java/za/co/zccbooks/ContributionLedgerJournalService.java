package za.co.zccbooks;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a source-linked draft journal when a captured contribution has a
 * complete, active organisation mapping. It never submits, approves or posts.
 */
@Service
public class ContributionLedgerJournalService {
    private final JdbcTemplate jdbc;
    private final AdminAuditService audit;

    public ContributionLedgerJournalService(JdbcTemplate jdbc, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public UUID createDraftIfMapped(UUID contributionId) {
        ContributionSource source = jdbc.query("""
            SELECT id, organisation_id, scope_unit_id, category_id, receipt_number,
                   amount, currency, contribution_date, payment_method, captured_by
            FROM contributions WHERE id = ? FOR UPDATE
            """, (rs, n) -> new ContributionSource(
                rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                rs.getObject("scope_unit_id", UUID.class), rs.getObject("category_id", UUID.class),
                rs.getString("receipt_number"), rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getObject("contribution_date", LocalDate.class), rs.getString("payment_method"),
                rs.getObject("captured_by", UUID.class)), contributionId)
            .stream().findFirst().orElseThrow(() ->
                new ResourceNotFoundException("Contribution receipt not found."));

        UUID existingJournal = jdbc.query("""
            SELECT id FROM finance_journals
            WHERE organisation_id = ? AND source_type = 'CONTRIBUTION_RECEIPT' AND source_id = ?
            """, (rs, n) -> rs.getObject("id", UUID.class), source.organisationId(), contributionId)
            .stream().findFirst().orElse(null);
        if (existingJournal != null) return existingJournal;

        Integer reversed = jdbc.queryForObject(
            "SELECT COUNT(*) FROM contribution_reversals WHERE contribution_id = ?",
            Integer.class, contributionId);
        if (reversed != null && reversed > 0) return null;

        Mapping mapping = jdbc.query("""
            SELECT m.debit_account_id, m.credit_account_id
            FROM finance_contribution_account_mappings m
            JOIN finance_accounts debit_account
              ON debit_account.id = m.debit_account_id
             AND debit_account.organisation_id = m.organisation_id
             AND debit_account.currency = m.currency
             AND debit_account.account_type = 'ASSET' AND debit_account.active = TRUE
            JOIN finance_accounts credit_account
              ON credit_account.id = m.credit_account_id
             AND credit_account.organisation_id = m.organisation_id
             AND credit_account.currency = m.currency
             AND credit_account.account_type = 'INCOME' AND credit_account.active = TRUE
            WHERE m.organisation_id = ? AND m.category_id = ?
              AND m.payment_method = ? AND m.currency = ?
            """, (rs, n) -> new Mapping(rs.getObject("debit_account_id", UUID.class),
                rs.getObject("credit_account_id", UUID.class)),
            source.organisationId(), source.categoryId(), source.paymentMethod(), source.currency())
            .stream().findFirst().orElse(null);

        if (mapping == null) {
            audit.record("CONTRIBUTION_LEDGER_MAPPING_MISSING", "CONTRIBUTION",
                contributionId, Map.of("organisationId", source.organisationId().toString(),
                    "scopeUnitId", source.scopeUnitId().toString(),
                    "receiptNumber", source.receiptNumber(),
                    "categoryId", source.categoryId().toString(),
                    "paymentMethod", source.paymentMethod(),
                    "currency", source.currency()));
            return null;
        }

        UUID journalId = UUID.randomUUID();
        String journalNumber = "AUTO-CONTRIB-" + contributionId.toString().replace("-", "").toUpperCase();
        String description = "Contribution receipt " + source.receiptNumber();
        jdbc.update("""
            INSERT INTO finance_journals
                (id, organisation_id, scope_unit_id, journal_number, journal_date, currency,
                 description, source_type, source_id, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'CONTRIBUTION_RECEIPT', ?, ?)
            """, journalId, source.organisationId(), source.scopeUnitId(), journalNumber,
            source.contributionDate(), source.currency(), description, contributionId, source.capturedBy());

        jdbc.update("""
            INSERT INTO finance_journal_lines
                (id, journal_id, account_id, organisation_id, currency,
                 debit_amount, credit_amount, description)
            VALUES (?, ?, ?, ?, ?, ?, 0, ?)
            """, UUID.randomUUID(), journalId, mapping.debitAccountId(), source.organisationId(),
            source.currency(), source.amount(), "Receipt asset recognition");

        jdbc.update("""
            INSERT INTO finance_journal_lines
                (id, journal_id, account_id, organisation_id, currency,
                 debit_amount, credit_amount, description)
            VALUES (?, ?, ?, ?, ?, 0, ?, ?)
            """, UUID.randomUUID(), journalId, mapping.creditAccountId(), source.organisationId(),
            source.currency(), source.amount(), "Contribution income recognition");

        audit.record("CONTRIBUTION_DRAFT_JOURNAL_CREATED", "FINANCE_JOURNAL", journalId,
            Map.of("organisationId", source.organisationId().toString(),
                "scopeUnitId", source.scopeUnitId().toString(),
                "contributionId", contributionId.toString(),
                "receiptNumber", source.receiptNumber(),
                "amount", source.amount().toPlainString(),
                "currency", source.currency(),
                "journalNumber", journalNumber));
        return journalId;
    }


    /**
     * Creates a draft journal that exactly reverses the posted contribution journal.
     * The original posted journal is never edited. A missing source journal is valid
     * when the receipt was captured before an account mapping existed.
     */
    @Transactional
    public UUID createReversalDraft(UUID contributionId, UUID reversalId, UUID actorId, String reason) {
        UUID existing = jdbc.query("""
            SELECT id FROM finance_journals
            WHERE source_type = 'CONTRIBUTION_REVERSAL' AND source_id = ?
            """, (rs, n) -> rs.getObject("id", UUID.class), reversalId)
            .stream().findFirst().orElse(null);
        if (existing != null) return existing;

        OriginalJournal original = jdbc.query("""
            SELECT id, organisation_id, scope_unit_id, journal_number, currency, status
            FROM finance_journals
            WHERE source_type = 'CONTRIBUTION_RECEIPT' AND source_id = ?
            FOR UPDATE
            """, (rs, n) -> new OriginalJournal(
                rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                rs.getObject("scope_unit_id", UUID.class), rs.getString("journal_number"),
                rs.getString("currency"), rs.getString("status")), contributionId)
            .stream().findFirst().orElse(null);

        if (original == null) return null;
        if (!"POSTED".equals(original.status())) {
            throw new ConflictException("The contribution journal must be posted before its receipt can be reversed. Reject or complete the original journal workflow first.");
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
            throw new ConflictException("The posted contribution journal has no complete line set and cannot be reversed automatically.");
        }

        UUID journalId = UUID.randomUUID();
        String journalNumber = "AUTO-CONTRIB-REV-" + reversalId.toString().replace("-", "").toUpperCase();
        jdbc.update("""
            INSERT INTO finance_journals
                (id, organisation_id, scope_unit_id, journal_number, journal_date, currency,
                 description, source_type, source_id, created_by)
            VALUES (?, ?, ?, ?, CURRENT_DATE, ?, ?, 'CONTRIBUTION_REVERSAL', ?, ?)
            """, journalId, original.organisationId(), original.scopeUnitId(), journalNumber,
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

        audit.record("CONTRIBUTION_REVERSAL_DRAFT_JOURNAL_CREATED", "FINANCE_JOURNAL", journalId,
            Map.of("organisationId", original.organisationId().toString(),
                "scopeUnitId", original.scopeUnitId().toString(),
                "contributionId", contributionId.toString(),
                "reversalId", reversalId.toString(),
                "originalJournalId", original.id().toString(),
                "originalJournalNumber", original.journalNumber(),
                "journalNumber", journalNumber));
        return journalId;
    }

    private record OriginalJournal(UUID id, UUID organisationId, UUID scopeUnitId,
        String journalNumber, String currency, String status) {}
    private record JournalLine(UUID accountId, BigDecimal debitAmount,
        BigDecimal creditAmount, String description) {}

    private record ContributionSource(UUID id, UUID organisationId, UUID scopeUnitId,
        UUID categoryId, String receiptNumber, BigDecimal amount, String currency,
        LocalDate contributionDate, String paymentMethod, UUID capturedBy) {}
    private record Mapping(UUID debitAccountId, UUID creditAccountId) {}
}
