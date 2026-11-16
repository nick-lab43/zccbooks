package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DepositReviewController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public DepositReviewController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/organisations/{organisationId}/deposit-review-queue")
    public List<DepositReviewItem> queue(@PathVariable UUID organisationId) {
        authorization.requireOrganisationPermission("FINANCE_REVIEW", organisationId);
        return jdbc.query("""
            SELECT b.id, b.scope_unit_id, u.name AS scope_name, b.deposit_date, b.bank_name,
                   b.bank_reference, b.expected_amount, b.status AS deposit_status, b.review_status,
                   b.note, b.review_response_note, b.submitted_at,
                   COUNT(a.id) AS receipt_count, COALESCE(SUM(a.allocated_amount), 0) AS allocated_amount,
                   creator.display_name AS submitted_by
            FROM deposit_batches b
            JOIN organisational_units u ON u.id = b.scope_unit_id
            JOIN admin_users creator ON creator.id = b.created_by
            LEFT JOIN deposit_allocations a ON a.deposit_batch_id = b.id
            WHERE b.organisation_id = ?
              AND b.review_status IN ('PENDING_REVIEW', 'QUERIED', 'REJECTED')
            GROUP BY b.id, b.scope_unit_id, u.name, creator.display_name
            ORDER BY b.submitted_at ASC NULLS LAST, b.created_at ASC
            """, (rs, n) -> new DepositReviewItem(
                rs.getObject("id", UUID.class).toString(), rs.getObject("scope_unit_id", UUID.class).toString(),
                rs.getString("scope_name"), rs.getDate("deposit_date").toLocalDate().toString(),
                rs.getString("bank_name"), rs.getString("bank_reference"), rs.getBigDecimal("expected_amount"),
                rs.getString("deposit_status"), rs.getString("review_status"), rs.getString("note"),
                rs.getString("review_response_note"), rs.getTimestamp("submitted_at") == null ? null
                    : rs.getTimestamp("submitted_at").toInstant().toString(),
                rs.getLong("receipt_count"), rs.getBigDecimal("allocated_amount"), rs.getString("submitted_by")),
            organisationId);
    }

    @GetMapping("/deposit-batches/{batchId}/review-history")
    public List<DepositReviewHistory> history(@PathVariable UUID batchId) {
        DepositContext batch = requireBatch(batchId);
        authorization.requireOrganisationPermission("FINANCE_REVIEW", batch.organisationId());
        return jdbc.query("""
            SELECT h.action, h.previous_status, h.new_status, h.note, h.changed_at,
                   COALESCE(u.display_name, 'System') AS changed_by
            FROM deposit_review_history h
            LEFT JOIN admin_users u ON u.id = h.changed_by
            WHERE h.deposit_batch_id = ?
            ORDER BY h.changed_at, h.id
            """, (rs, n) -> new DepositReviewHistory(rs.getString("action"),
                rs.getString("previous_status"), rs.getString("new_status"), rs.getString("note"),
                rs.getTimestamp("changed_at").toInstant().toString(), rs.getString("changed_by")), batchId);
    }

    @PostMapping("/deposit-batches/{batchId}/review")
    @Transactional
    public DepositReviewItem review(@PathVariable UUID batchId, @Valid @RequestBody ReviewDecision request) {
        DepositContext batch = requireBatch(batchId);
        authorization.requireOrganisationPermission("FINANCE_REVIEW", batch.organisationId());
        if (!List.of("PENDING_REVIEW").contains(batch.reviewStatus())) {
            throw new ConflictException("Only a package awaiting central review can receive a decision.");
        }
        String decision = request.decision().trim().toUpperCase();
        if (!List.of("ACCEPT", "QUERY", "REJECT").contains(decision)) {
            throw new IllegalArgumentException("Decision must be ACCEPT, QUERY or REJECT.");
        }
        String note = blankToNull(request.note());
        if (!"ACCEPT".equals(decision) && note == null) {
            throw new IllegalArgumentException("A reason is required when querying or rejecting a deposit package.");
        }
        String next = switch (decision) {
            case "ACCEPT" -> "ACCEPTED";
            case "QUERY" -> "QUERIED";
            default -> "REJECTED";
        };
        UUID actor = actorId();
        jdbc.update("UPDATE deposit_batches SET review_status = ?, review_response_note = NULL WHERE id = ?",
            next, batchId);
        jdbc.update("""
            INSERT INTO deposit_review_history
                (id, deposit_batch_id, action, previous_status, new_status, note, changed_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), batchId, next, batch.reviewStatus(), next, note, actor);
        audit.record("DEPOSIT_PACKAGE_" + next, "DEPOSIT_BATCH", batchId,
            Map.of("scopeUnitId", batch.scopeUnitId().toString(), "note", note == null ? "" : note));
        return requireReviewItem(batchId);
    }

    @PostMapping("/deposit-batches/{batchId}/review-response")
    @Transactional
    public DepositReviewItem respond(@PathVariable UUID batchId, @Valid @RequestBody ReviewResponse request) {
        DepositContext batch = requireBatch(batchId);
        authorization.requireUnitPermission("FINANCE_DEPOSIT", batch.scopeUnitId());
        if (!List.of("QUERIED", "REJECTED").contains(batch.reviewStatus())) {
            throw new ConflictException("A response can only be submitted for a queried or rejected package.");
        }
        String note = request.note().trim();
        UUID actor = actorId();
        jdbc.update("UPDATE deposit_batches SET review_status = 'PENDING_REVIEW', review_response_note = ? WHERE id = ?",
            note, batchId);
        jdbc.update("""
            INSERT INTO deposit_review_history
                (id, deposit_batch_id, action, previous_status, new_status, note, changed_by)
            VALUES (?, ?, 'RESPONDED', ?, 'PENDING_REVIEW', ?, ?)
            """, UUID.randomUUID(), batchId, batch.reviewStatus(), note, actor);
        audit.record("DEPOSIT_PACKAGE_RESPONSE_SUBMITTED", "DEPOSIT_BATCH", batchId,
            Map.of("scopeUnitId", batch.scopeUnitId().toString()));
        return requireReviewItem(batchId);
    }

    private DepositContext requireBatch(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, scope_unit_id, review_status
            FROM deposit_batches WHERE id = ? FOR UPDATE
            """, (rs, n) -> new DepositContext(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getObject("scope_unit_id", UUID.class),
                rs.getString("review_status")), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Deposit batch not found."));
    }

    private DepositReviewItem requireReviewItem(UUID id) {
        return jdbc.query("""
            SELECT b.id, b.scope_unit_id, u.name AS scope_name, b.deposit_date, b.bank_name,
                   b.bank_reference, b.expected_amount, b.status AS deposit_status, b.review_status,
                   b.note, b.review_response_note, b.submitted_at,
                   COUNT(a.id) AS receipt_count, COALESCE(SUM(a.allocated_amount), 0) AS allocated_amount,
                   creator.display_name AS submitted_by
            FROM deposit_batches b
            JOIN organisational_units u ON u.id = b.scope_unit_id
            JOIN admin_users creator ON creator.id = b.created_by
            LEFT JOIN deposit_allocations a ON a.deposit_batch_id = b.id
            WHERE b.id = ?
            GROUP BY b.id, b.scope_unit_id, u.name, creator.display_name
            """, (rs, n) -> new DepositReviewItem(
                rs.getObject("id", UUID.class).toString(), rs.getObject("scope_unit_id", UUID.class).toString(),
                rs.getString("scope_name"), rs.getDate("deposit_date").toLocalDate().toString(),
                rs.getString("bank_name"), rs.getString("bank_reference"), rs.getBigDecimal("expected_amount"),
                rs.getString("deposit_status"), rs.getString("review_status"), rs.getString("note"),
                rs.getString("review_response_note"), rs.getTimestamp("submitted_at") == null ? null
                    : rs.getTimestamp("submitted_at").toInstant().toString(),
                rs.getLong("receipt_count"), rs.getBigDecimal("allocated_amount"), rs.getString("submitted_by")), id)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Deposit batch not found."));
    }

    private UUID actorId() {
        return jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record DepositContext(UUID id, UUID organisationId, UUID scopeUnitId, String reviewStatus) {}
    public record ReviewDecision(@NotBlank @Size(max = 20) String decision, @Size(max = 1000) String note) {}
    public record ReviewResponse(@NotBlank @Size(max = 1000) String note) {}
    public record DepositReviewItem(String id, String scopeUnitId, String scopeName, String depositDate,
        String bankName, String bankReference, BigDecimal expectedAmount, String depositStatus,
        String reviewStatus, String note, String responseNote, String submittedAt,
        long receiptCount, BigDecimal allocatedAmount, String submittedBy) {}
    public record DepositReviewHistory(String action, String previousStatus, String newStatus,
        String note, String changedAt, String changedBy) {}
}
