package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DepositManagementController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public DepositManagementController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/organisations/{organisationId}/deposit-batches")
    public List<DepositBatchDto> batches(@PathVariable UUID organisationId, @RequestParam UUID scopeUnitId) {
        authorization.requireUnitPermission("FINANCE_VIEW", scopeUnitId);
        return jdbc.query("""
            SELECT b.id, b.scope_unit_id, u.name AS scope_name, b.deposit_date, b.bank_name,
                   b.bank_reference, b.expected_amount, b.status, b.note, b.created_at,
                   COALESCE(SUM(a.allocated_amount), 0) AS allocated_amount,
                   COUNT(a.id) AS receipt_count
            FROM deposit_batches b
            JOIN organisational_units u ON u.id = b.scope_unit_id
            LEFT JOIN deposit_allocations a ON a.deposit_batch_id = b.id
            WHERE b.organisation_id = ? AND b.scope_unit_id = ?
            GROUP BY b.id, b.scope_unit_id, u.name
            ORDER BY b.deposit_date DESC, b.created_at DESC
            """, (rs, n) -> new DepositBatchDto(rs.getObject("id", UUID.class).toString(),
                rs.getObject("scope_unit_id", UUID.class).toString(), rs.getString("scope_name"),
                rs.getDate("deposit_date").toLocalDate().toString(), rs.getString("bank_name"),
                rs.getString("bank_reference"), rs.getBigDecimal("expected_amount"),
                rs.getString("status"), rs.getString("note"), rs.getBigDecimal("allocated_amount"),
                rs.getLong("receipt_count"), rs.getTimestamp("created_at").toInstant().toString()),
            organisationId, scopeUnitId);
    }

    @PostMapping("/organisations/{organisationId}/deposit-batches")
    @Transactional
    public DepositBatchDto createBatch(@PathVariable UUID organisationId, @Valid @RequestBody CreateDepositBatch request) {
        authorization.requireUnitPermission("FINANCE_DEPOSIT", request.scopeUnitId());
        Integer validUnit = jdbc.queryForObject("""
            SELECT COUNT(*) FROM organisational_units
            WHERE id = ? AND organisation_id = ? AND active = TRUE
            """, Integer.class, request.scopeUnitId(), organisationId);
        if (validUnit == null || validUnit == 0) throw new ResourceNotFoundException("Active financial scope not found.");
        UUID actor = actorId();
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deposit_batches
                (id, organisation_id, scope_unit_id, deposit_date, bank_name, bank_reference,
                 expected_amount, note, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, organisationId, request.scopeUnitId(), request.depositDate(),
            request.bankName().trim(), request.bankReference().trim(), request.expectedAmount(),
            blankToNull(request.note()), actor);
        audit.record("DEPOSIT_BATCH_CREATED", "DEPOSIT_BATCH", id,
            Map.of("organisationId", organisationId.toString(), "scopeUnitId", request.scopeUnitId().toString(),
                "bankReference", request.bankReference(), "expectedAmount", request.expectedAmount().toPlainString()));
        return requireBatch(id);
    }

    @PostMapping("/deposit-batches/{batchId}/allocations")
    @Transactional
    public DepositBatchDto allocate(@PathVariable UUID batchId, @Valid @RequestBody AllocateReceipt request) {
        DepositBatchContext batch = requireBatchContext(batchId, true);
        authorization.requireUnitPermission("FINANCE_DEPOSIT", batch.scopeUnitId());
        if (!"DRAFT".equals(batch.status())) throw new ConflictException("Only draft deposit batches can be changed.");
        ContributionDepositContext contribution = jdbc.query("""
            SELECT id, organisation_id, scope_unit_id, amount, payment_method
            FROM contributions WHERE id = ? FOR UPDATE
            """, (rs, n) -> new ContributionDepositContext(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getObject("scope_unit_id", UUID.class),
                rs.getBigDecimal("amount"), rs.getString("payment_method")), request.contributionId())
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Contribution receipt not found."));
        if (!batch.organisationId().equals(contribution.organisationId())
                || !batch.scopeUnitId().equals(contribution.scopeUnitId())) {
            throw new IllegalArgumentException("The receipt must belong to the same organisation and geographical unit as the deposit batch.");
        }
        if (!"CASH".equals(contribution.paymentMethod())) {
            throw new IllegalArgumentException("Only cash contributions can be allocated to a cash deposit batch.");
        }
        if (contribution.amount().compareTo(request.allocatedAmount()) != 0) {
            throw new IllegalArgumentException("The allocation must equal the full receipt amount. Partial allocations are not supported.");
        }
        jdbc.update("""
            INSERT INTO deposit_allocations (id, deposit_batch_id, contribution_id, allocated_amount)
            VALUES (?, ?, ?, ?)
            """, UUID.randomUUID(), batchId, contribution.id(), request.allocatedAmount());
        audit.record("DEPOSIT_RECEIPT_ALLOCATED", "DEPOSIT_BATCH", batchId,
            Map.of("contributionId", contribution.id().toString(), "amount", request.allocatedAmount().toPlainString()));
        return requireBatch(batchId);
    }

    @DeleteMapping("/deposit-batches/{batchId}/allocations/{contributionId}")
    @Transactional
    public DepositBatchDto removeAllocation(@PathVariable UUID batchId, @PathVariable UUID contributionId) {
        DepositBatchContext batch = requireBatchContext(batchId, true);
        authorization.requireUnitPermission("FINANCE_DEPOSIT", batch.scopeUnitId());
        if (!"DRAFT".equals(batch.status())) throw new ConflictException("Only draft deposit batches can be changed.");
        int deleted = jdbc.update("DELETE FROM deposit_allocations WHERE deposit_batch_id = ? AND contribution_id = ?",
            batchId, contributionId);
        if (deleted == 0) throw new ResourceNotFoundException("Receipt allocation not found in this batch.");
        audit.record("DEPOSIT_RECEIPT_REMOVED", "DEPOSIT_BATCH", batchId,
            Map.of("contributionId", contributionId.toString()));
        return requireBatch(batchId);
    }

    @PostMapping("/deposit-batches/{batchId}/submit")
    @Transactional
    public DepositBatchDto submit(@PathVariable UUID batchId) {
        DepositBatchContext batch = requireBatchContext(batchId, true);
        authorization.requireUnitPermission("FINANCE_DEPOSIT", batch.scopeUnitId());
        if (!"DRAFT".equals(batch.status())) throw new ConflictException("Only draft deposit batches can be submitted.");
        DepositTotals totals = jdbc.queryForObject("""
            SELECT COUNT(*) AS receipt_count, COALESCE(SUM(allocated_amount), 0) AS allocated_amount
            FROM deposit_allocations WHERE deposit_batch_id = ?
            """, (rs, n) -> new DepositTotals(rs.getLong("receipt_count"), rs.getBigDecimal("allocated_amount")), batchId);
        if (totals == null || totals.receiptCount() == 0) throw new IllegalArgumentException("A deposit batch must contain at least one receipt.");
        if (totals.allocatedAmount().compareTo(batch.expectedAmount()) != 0) {
            throw new IllegalArgumentException("The allocated receipt total must equal the declared deposit amount.");
        }
        jdbc.update("""
            UPDATE deposit_batches SET status = 'SUBMITTED', submitted_at = CURRENT_TIMESTAMP WHERE id = ?
            """, batchId);
        audit.record("DEPOSIT_BATCH_SUBMITTED", "DEPOSIT_BATCH", batchId,
            Map.of("receiptCount", Long.toString(totals.receiptCount()),
                "allocatedAmount", totals.allocatedAmount().toPlainString()));
        return requireBatch(batchId);
    }

    @PostMapping("/organisations/{organisationId}/bank-statement-entries")
    @Transactional
    public BankStatementEntryDto recordStatementEntry(@PathVariable UUID organisationId,
            @Valid @RequestBody RecordStatementEntry request) {
        authorization.requireUnitPermission("FINANCE_RECONCILE", request.scopeUnitId());
        UUID id = UUID.randomUUID();
        UUID actor = actorId();
        jdbc.update("""
            INSERT INTO bank_statement_entries
                (id, organisation_id, scope_unit_id, transaction_date, bank_reference, amount, description, recorded_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, id, organisationId, request.scopeUnitId(), request.transactionDate(),
            request.bankReference().trim(), request.amount(), blankToNull(request.description()), actor);
        audit.record("BANK_STATEMENT_ENTRY_RECORDED", "BANK_STATEMENT_ENTRY", id,
            Map.of("scopeUnitId", request.scopeUnitId().toString(),
                "bankReference", request.bankReference(), "amount", request.amount().toPlainString()));
        return requireStatementEntry(id);
    }

    @GetMapping("/organisations/{organisationId}/bank-statement-entries")
    public List<BankStatementEntryDto> statementEntries(@PathVariable UUID organisationId,
            @RequestParam UUID scopeUnitId) {
        authorization.requireUnitPermission("FINANCE_RECONCILE", scopeUnitId);
        return jdbc.query("""
            SELECT e.id, e.scope_unit_id, u.name AS scope_name, e.transaction_date,
                   e.bank_reference, e.amount, e.description, e.recorded_at,
                   (r.id IS NOT NULL) AS reconciled
            FROM bank_statement_entries e
            JOIN organisational_units u ON u.id = e.scope_unit_id
            LEFT JOIN deposit_reconciliations r ON r.statement_entry_id = e.id
            WHERE e.organisation_id = ? AND e.scope_unit_id = ?
            ORDER BY e.transaction_date DESC, e.recorded_at DESC
            """, (rs, n) -> new BankStatementEntryDto(rs.getObject("id", UUID.class).toString(),
                rs.getObject("scope_unit_id", UUID.class).toString(), rs.getString("scope_name"),
                rs.getDate("transaction_date").toLocalDate().toString(), rs.getString("bank_reference"),
                rs.getBigDecimal("amount"), rs.getString("description"), rs.getBoolean("reconciled")),
            organisationId, scopeUnitId);
    }

    @PostMapping("/deposit-batches/{batchId}/reconcile")
    @Transactional
    public DepositBatchDto reconcile(@PathVariable UUID batchId, @Valid @RequestBody ReconcileDeposit request) {
        DepositBatchContext batch = requireBatchContext(batchId, true);
        authorization.requireUnitPermission("FINANCE_RECONCILE", batch.scopeUnitId());
        if (!"SUBMITTED".equals(batch.status())) throw new ConflictException("Only submitted deposit batches can be reconciled.");
        BankStatementEntryDto entry = jdbc.query("""
            SELECT e.id, e.scope_unit_id, u.name AS scope_name, e.transaction_date,
                   e.bank_reference, e.amount, e.description, e.recorded_at,
                   (r.id IS NOT NULL) AS reconciled
            FROM bank_statement_entries e
            JOIN organisational_units u ON u.id = e.scope_unit_id
            LEFT JOIN deposit_reconciliations r ON r.statement_entry_id = e.id
            WHERE e.id = ? AND e.organisation_id = ? FOR UPDATE OF e
            """, (rs, n) -> new BankStatementEntryDto(rs.getObject("id", UUID.class).toString(),
                rs.getObject("scope_unit_id", UUID.class).toString(), rs.getString("scope_name"),
                rs.getDate("transaction_date").toLocalDate().toString(), rs.getString("bank_reference"),
                rs.getBigDecimal("amount"), rs.getString("description"), rs.getBoolean("reconciled")),
            request.statementEntryId(), batch.organisationId()).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Bank statement entry not found."));
        if (!batch.scopeUnitId().toString().equals(entry.scopeUnitId())) {
            throw new IllegalArgumentException("The bank statement entry must belong to the same geographical unit.");
        }
        if (entry.reconciled()) throw new ConflictException("This bank statement entry has already been reconciled.");
        if (!batch.bankReference().equalsIgnoreCase(entry.bankReference())) {
            throw new IllegalArgumentException("Bank references do not match.");
        }
        if (batch.expectedAmount().compareTo(entry.amount()) != 0) {
            throw new IllegalArgumentException("The deposit and bank statement amounts do not match.");
        }
        LocalDate depositDate = LocalDate.parse(batch.depositDate());
        LocalDate bankDate = LocalDate.parse(entry.transactionDate());
        if (Math.abs(java.time.temporal.ChronoUnit.DAYS.between(depositDate, bankDate)) > 3) {
            throw new IllegalArgumentException("The bank transaction date must be within three days of the recorded deposit date.");
        }
        jdbc.update("""
            INSERT INTO deposit_reconciliations (id, deposit_batch_id, statement_entry_id, matched_by, match_note)
            VALUES (?, ?, ?, ?, ?)
            """, UUID.randomUUID(), batchId, request.statementEntryId(), actorId(), blankToNull(request.note()));
        jdbc.update("UPDATE deposit_batches SET status = 'RECONCILED' WHERE id = ?", batchId);
        jdbc.update("""
            UPDATE contribution_deposit_followups f
            SET status = 'RESOLVED', assigned_to = ?, note = 'Resolved by verified bank deposit reconciliation',
                updated_at = CURRENT_TIMESTAMP
            FROM deposit_allocations a
            WHERE a.deposit_batch_id = ? AND f.contribution_id = a.contribution_id
              AND f.status <> 'RESOLVED'
            """, actorId(), batchId);
        audit.record("DEPOSIT_BATCH_RECONCILED", "DEPOSIT_BATCH", batchId,
            Map.of("statementEntryId", request.statementEntryId().toString(),
                "bankReference", entry.bankReference(), "amount", entry.amount().toPlainString()));
        return requireBatch(batchId);
    }

    private DepositBatchDto requireBatch(UUID id) {
        return jdbc.query("""
            SELECT b.id, b.scope_unit_id, u.name AS scope_name, b.deposit_date, b.bank_name,
                   b.bank_reference, b.expected_amount, b.status, b.note, b.created_at,
                   COALESCE(SUM(a.allocated_amount), 0) AS allocated_amount,
                   COUNT(a.id) AS receipt_count
            FROM deposit_batches b JOIN organisational_units u ON u.id = b.scope_unit_id
            LEFT JOIN deposit_allocations a ON a.deposit_batch_id = b.id
            WHERE b.id = ?
            GROUP BY b.id, b.scope_unit_id, u.name
            """, (rs, n) -> new DepositBatchDto(rs.getObject("id", UUID.class).toString(),
                rs.getObject("scope_unit_id", UUID.class).toString(), rs.getString("scope_name"),
                rs.getDate("deposit_date").toLocalDate().toString(), rs.getString("bank_name"),
                rs.getString("bank_reference"), rs.getBigDecimal("expected_amount"), rs.getString("status"),
                rs.getString("note"), rs.getBigDecimal("allocated_amount"), rs.getLong("receipt_count"),
                rs.getTimestamp("created_at").toInstant().toString()), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Deposit batch not found."));
    }

    private DepositBatchContext requireBatchContext(UUID id, boolean lock) {
        String sql = """
            SELECT id, organisation_id, scope_unit_id, deposit_date, bank_reference, expected_amount, status
            FROM deposit_batches WHERE id = ?
            """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, n) -> new DepositBatchContext(rs.getObject("id", UUID.class),
            rs.getObject("organisation_id", UUID.class), rs.getObject("scope_unit_id", UUID.class),
            rs.getDate("deposit_date").toLocalDate().toString(), rs.getString("bank_reference"),
            rs.getBigDecimal("expected_amount"), rs.getString("status")), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Deposit batch not found."));
    }

    private BankStatementEntryDto requireStatementEntry(UUID id) {
        return jdbc.query("""
            SELECT e.id, e.scope_unit_id, u.name AS scope_name, e.transaction_date,
                   e.bank_reference, e.amount, e.description, e.recorded_at,
                   (r.id IS NOT NULL) AS reconciled
            FROM bank_statement_entries e JOIN organisational_units u ON u.id = e.scope_unit_id
            LEFT JOIN deposit_reconciliations r ON r.statement_entry_id = e.id
            WHERE e.id = ?
            """, (rs, n) -> new BankStatementEntryDto(rs.getObject("id", UUID.class).toString(),
                rs.getObject("scope_unit_id", UUID.class).toString(), rs.getString("scope_name"),
                rs.getDate("transaction_date").toLocalDate().toString(), rs.getString("bank_reference"),
                rs.getBigDecimal("amount"), rs.getString("description"), rs.getBoolean("reconciled")), id)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Statement entry not found."));
    }

    private UUID actorId() {
        return jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreateDepositBatch(@NotNull UUID scopeUnitId, @NotNull LocalDate depositDate,
        @NotBlank @Size(max = 120) String bankName, @NotBlank @Size(max = 120) String bankReference,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal expectedAmount,
        @Size(max = 500) String note) {}
    public record AllocateReceipt(@NotNull UUID contributionId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal allocatedAmount) {}
    public record RecordStatementEntry(@NotNull UUID scopeUnitId, @NotNull LocalDate transactionDate,
        @NotBlank @Size(max = 120) String bankReference,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @Size(max = 500) String description) {}
    public record ReconcileDeposit(@NotNull UUID statementEntryId, @Size(max = 500) String note) {}
    public record DepositBatchDto(String id, String scopeUnitId, String scopeName, String depositDate,
        String bankName, String bankReference, BigDecimal expectedAmount, String status, String note,
        BigDecimal allocatedAmount, long receiptCount, String createdAt) {}
    public record BankStatementEntryDto(String id, String scopeUnitId, String scopeName,
        String transactionDate, String bankReference, BigDecimal amount, String description, boolean reconciled) {}
    private record DepositTotals(long receiptCount, BigDecimal allocatedAmount) {}
    private record DepositBatchContext(UUID id, UUID organisationId, UUID scopeUnitId, String depositDate,
        String bankReference, BigDecimal expectedAmount, String status) {}
    private record ContributionDepositContext(UUID id, UUID organisationId, UUID scopeUnitId,
        BigDecimal amount, String paymentMethod) {}
}
