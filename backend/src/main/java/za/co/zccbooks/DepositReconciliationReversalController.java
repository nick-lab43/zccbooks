package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DepositReconciliationReversalController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;
    private final DepositSettlementLedgerJournalService settlementJournals;

    public DepositReconciliationReversalController(JdbcTemplate jdbc, AuthorizationService authorization,
            AdminAuditService audit, DepositSettlementLedgerJournalService settlementJournals) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.settlementJournals = settlementJournals;
    }

    @PostMapping("/deposit-reconciliations/{reconciliationId}/reverse")
    @Transactional
    public ReversalResult reverse(@PathVariable UUID reconciliationId,
            @Valid @RequestBody ReverseDepositReconciliation request) {
        ReconciliationSource source = jdbc.query("""
            SELECT r.id, b.id AS batch_id, b.organisation_id, b.scope_unit_id,
                   b.expected_amount, e.amount
            FROM deposit_reconciliations r
            JOIN deposit_batches b ON b.id = r.deposit_batch_id
            JOIN bank_statement_entries e ON e.id = r.statement_entry_id
            WHERE r.id = ?
            FOR UPDATE OF r, b
            """, (rs, n) -> new ReconciliationSource(rs.getObject("id", UUID.class),
                rs.getObject("batch_id", UUID.class), rs.getObject("organisation_id", UUID.class),
                rs.getObject("scope_unit_id", UUID.class), rs.getBigDecimal("expected_amount"),
                rs.getBigDecimal("amount")), reconciliationId).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Deposit reconciliation not found."));

        authorization.requireUnitPermission("FINANCE_REVERSE", source.scopeUnitId());
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM deposit_reconciliation_reversals WHERE reconciliation_id = ?",
            Integer.class, reconciliationId);
        if (count != null && count > 0) throw new ConflictException(
            "This deposit reconciliation has already been reversed.");

        UUID actor = jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
        UUID reversalId = UUID.randomUUID();
        String reason = request.reason().trim();
        jdbc.update("""
            INSERT INTO deposit_reconciliation_reversals (id, reconciliation_id, reason, reversed_by)
            VALUES (?, ?, ?, ?)
            """, reversalId, reconciliationId, reason, actor);
        settlementJournals.createReversalDraft(reconciliationId, reversalId, actor, reason);

        BigDecimal banked = jdbc.queryForObject("""
            SELECT COALESCE(SUM(e.amount), 0)
            FROM deposit_reconciliations r
            JOIN bank_statement_entries e ON e.id = r.statement_entry_id
            WHERE r.deposit_batch_id = ?
              AND NOT EXISTS (SELECT 1 FROM deposit_reconciliation_reversals x WHERE x.reconciliation_id = r.id)
            """, BigDecimal.class, source.batchId());
        BigDecimal outstanding = source.expectedAmount().subtract(banked);
        String status = outstanding.signum() == 0 ? "RECONCILED"
            : banked.signum() == 0 ? "SUBMITTED" : "PARTIALLY_RECONCILED";
        jdbc.update("UPDATE deposit_batches SET status = ? WHERE id = ?", status, source.batchId());

        String followupStatus = "RECONCILED".equals(status) ? "RESOLVED"
            : banked.signum() == 0 ? "SUBMITTED" : "PARTIALLY_BANKED";
        jdbc.update("""
            UPDATE contribution_deposit_followups f
            SET status = ?, assigned_to = ?, note = ?, updated_at = CURRENT_TIMESTAMP
            FROM deposit_allocations a
            WHERE a.deposit_batch_id = ? AND f.contribution_id = a.contribution_id
            """, followupStatus, actor, "Deposit reconciliation reversed: " + reason, source.batchId());

        audit.record("DEPOSIT_RECONCILIATION_REVERSED", "DEPOSIT_RECONCILIATION", reconciliationId,
            Map.of("reversalId", reversalId.toString(), "batchId", source.batchId().toString(),
                "reversedAmount", source.amount().toPlainString(), "remainingBankedAmount", banked.toPlainString(),
                "outstandingAmount", outstanding.toPlainString(), "reason", reason));
        return new ReversalResult(reversalId.toString(), reconciliationId.toString(),
            source.batchId().toString(), source.amount(), banked, outstanding, status);
    }

    public record ReverseDepositReconciliation(@NotBlank @Size(max = 1000) String reason) {}
    public record ReversalResult(String reversalId, String reconciliationId, String depositBatchId,
        BigDecimal reversedAmount, BigDecimal remainingBankedAmount,
        BigDecimal outstandingAmount, String batchStatus) {}
    private record ReconciliationSource(UUID id, UUID batchId, UUID organisationId,
        UUID scopeUnitId, BigDecimal expectedAmount, BigDecimal amount) {}
}
