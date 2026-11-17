package za.co.zccbooks;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates draft asset-transfer journals for verified bank deposit reconciliations. */
@Service
public class DepositSettlementLedgerJournalService {
    private final JdbcTemplate jdbc;
    private final AdminAuditService audit;

    public DepositSettlementLedgerJournalService(JdbcTemplate jdbc, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public UUID createDraftIfMapped(UUID reconciliationId) {
        SettlementSource source = jdbc.query("""
            SELECT r.id AS reconciliation_id, r.matched_by, b.id AS batch_id,
                   b.organisation_id, b.scope_unit_id, b.bank_name,
                   e.id AS statement_entry_id, e.transaction_date, e.amount, e.bank_reference
            FROM deposit_reconciliations r
            JOIN deposit_batches b ON b.id = r.deposit_batch_id
            JOIN bank_statement_entries e ON e.id = r.statement_entry_id
            WHERE r.id = ? FOR UPDATE OF r
            """, (rs, n) -> new SettlementSource(rs.getObject("reconciliation_id", UUID.class),
                rs.getObject("matched_by", UUID.class), rs.getObject("batch_id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getObject("scope_unit_id", UUID.class),
                rs.getString("bank_name"), rs.getObject("statement_entry_id", UUID.class),
                rs.getObject("transaction_date", LocalDate.class), rs.getBigDecimal("amount"),
                rs.getString("bank_reference")), reconciliationId)
            .stream().findFirst().orElseThrow(() ->
                new ResourceNotFoundException("Deposit reconciliation not found."));

        UUID existing = jdbc.query("""
            SELECT id FROM finance_journals
            WHERE organisation_id = ? AND source_type = 'DEPOSIT_SETTLEMENT' AND source_id = ?
            """, (rs, n) -> rs.getObject("id", UUID.class), source.organisationId(), reconciliationId)
            .stream().findFirst().orElse(null);
        if (existing != null) return existing;

        Mapping mapping = jdbc.query("""
            SELECT m.bank_account_id, m.cash_account_id
            FROM finance_deposit_settlement_mappings m
            JOIN finance_accounts bank_account ON bank_account.id = m.bank_account_id
                AND bank_account.organisation_id = m.organisation_id
                AND bank_account.currency = m.currency
                AND bank_account.account_type = 'ASSET' AND bank_account.active = TRUE
            JOIN finance_accounts cash_account ON cash_account.id = m.cash_account_id
                AND cash_account.organisation_id = m.organisation_id
                AND cash_account.currency = m.currency
                AND cash_account.account_type = 'ASSET' AND cash_account.active = TRUE
            WHERE m.organisation_id = ? AND LOWER(m.bank_name) = LOWER(?) AND m.currency = 'ZAR'
            """, (rs, n) -> new Mapping(rs.getObject("bank_account_id", UUID.class),
                rs.getObject("cash_account_id", UUID.class)), source.organisationId(), source.bankName())
            .stream().findFirst().orElse(null);

        if (mapping == null) {
            audit.record("DEPOSIT_SETTLEMENT_LEDGER_MAPPING_MISSING", "DEPOSIT_RECONCILIATION",
                reconciliationId, Map.of("organisationId", source.organisationId().toString(),
                    "scopeUnitId", source.scopeUnitId().toString(), "bankName", source.bankName(),
                    "bankReference", source.bankReference(), "amount", source.amount().toPlainString()));
            return null;
        }

        UUID journalId = UUID.randomUUID();
        String journalNumber = "AUTO-DEP-" + reconciliationId.toString().replace("-", "").toUpperCase();
        jdbc.update("""
            INSERT INTO finance_journals
                (id, organisation_id, scope_unit_id, journal_number, journal_date, currency,
                 description, source_type, source_id, created_by)
            VALUES (?, ?, ?, ?, ?, 'ZAR', ?, 'DEPOSIT_SETTLEMENT', ?, ?)
            """, journalId, source.organisationId(), source.scopeUnitId(), journalNumber,
            source.transactionDate(), "Bank deposit settlement " + source.bankReference(),
            reconciliationId, source.matchedBy());
        jdbc.update("""
            INSERT INTO finance_journal_lines
                (id, journal_id, account_id, organisation_id, currency,
                 debit_amount, credit_amount, description)
            VALUES (?, ?, ?, ?, 'ZAR', ?, 0, ?)
            """, UUID.randomUUID(), journalId, mapping.bankAccountId(), source.organisationId(),
            source.amount(), "Verified bank deposit");
        jdbc.update("""
            INSERT INTO finance_journal_lines
                (id, journal_id, account_id, organisation_id, currency,
                 debit_amount, credit_amount, description)
            VALUES (?, ?, ?, ?, 'ZAR', 0, ?, ?)
            """, UUID.randomUUID(), journalId, mapping.cashAccountId(), source.organisationId(),
            source.amount(), "Cash deposited to bank");
        audit.record("DEPOSIT_SETTLEMENT_DRAFT_JOURNAL_CREATED", "FINANCE_JOURNAL", journalId,
            Map.of("organisationId", source.organisationId().toString(),
                "scopeUnitId", source.scopeUnitId().toString(),
                "reconciliationId", reconciliationId.toString(),
                "depositBatchId", source.batchId().toString(),
                "statementEntryId", source.statementEntryId().toString(),
                "bankReference", source.bankReference(), "amount", source.amount().toPlainString(),
                "journalNumber", journalNumber));
        return journalId;
    }

    @Transactional
    public int retryForBankMapping(UUID organisationId, String bankName) {
        List<UUID> pending = jdbc.query("""
            SELECT r.id FROM deposit_reconciliations r
            JOIN deposit_batches b ON b.id = r.deposit_batch_id
            WHERE b.organisation_id = ? AND LOWER(b.bank_name) = LOWER(?)
              AND NOT EXISTS (
                  SELECT 1 FROM finance_journals j
                  WHERE j.organisation_id = b.organisation_id
                    AND j.source_type = 'DEPOSIT_SETTLEMENT' AND j.source_id = r.id
              )
            ORDER BY r.matched_at, r.id
            """, (rs, n) -> rs.getObject("id", UUID.class), organisationId, bankName);
        int created = 0;
        for (UUID id : pending) if (createDraftIfMapped(id) != null) created++;
        return created;
    }

    private record SettlementSource(UUID reconciliationId, UUID matchedBy, UUID batchId,
        UUID organisationId, UUID scopeUnitId, String bankName, UUID statementEntryId,
        LocalDate transactionDate, java.math.BigDecimal amount, String bankReference) {}
    private record Mapping(UUID bankAccountId, UUID cashAccountId) {}
}
