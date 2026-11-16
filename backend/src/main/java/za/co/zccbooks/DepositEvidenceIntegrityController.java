package za.co.zccbooks;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/deposit-batches/{batchId}/integrity-checks")
public class DepositEvidenceIntegrityController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public DepositEvidenceIntegrityController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @PostMapping
    @Transactional
    public IntegrityCheckRun verify(@PathVariable UUID batchId) {
        BatchScope batch = requireBatch(batchId);
        authorization.requireUnitPermission("FINANCE_VIEW", batch.scopeUnitId());

        Long totalBytes = jdbc.queryForObject("""
            SELECT COALESCE(SUM(file_size), 0) FROM (
                SELECT file_size FROM deposit_evidence WHERE deposit_batch_id = ?
                UNION ALL
                SELECT file_size FROM deposit_documents WHERE deposit_batch_id = ?
            ) stored_files
            """, Long.class, batchId, batchId);
        if (totalBytes != null && totalBytes > 100L * 1024 * 1024) {
            throw new ConflictException("This deposit contains more than 100 MB of stored files. Verify the evidence in smaller batches.");
        }

        List<StoredFile> files = jdbc.query("""
            SELECT 'EVIDENCE' AS target_type, id AS target_id, original_filename AS filename,
                   sha256 AS stored_sha256, file_data
            FROM deposit_evidence WHERE deposit_batch_id = ?
            UNION ALL
            SELECT 'SCANNED_DOCUMENT' AS target_type, id AS target_id, original_filename AS filename,
                   sha256 AS stored_sha256, file_data
            FROM deposit_documents WHERE deposit_batch_id = ?
            ORDER BY target_type, filename
            """, (rs, row) -> new StoredFile(rs.getString("target_type"),
                rs.getObject("target_id", UUID.class), rs.getString("filename"),
                rs.getString("stored_sha256"), rs.getBytes("file_data")), batchId, batchId);

        if (files.isEmpty()) {
            throw new ConflictException("There are no stored deposit files to verify.");
        }

        UUID actorId = jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
        UUID runId = UUID.randomUUID();
        List<IntegrityCheckResult> results = new ArrayList<>();
        int verified = 0;
        for (StoredFile file : files) {
            String computed = sha256(file.bytes());
            boolean matches = MessageDigest.isEqual(
                file.storedSha256().toLowerCase().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                computed.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if (matches) verified++;
            UUID checkId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO deposit_evidence_integrity_checks
                    (id, check_run_id, deposit_batch_id, target_type, target_id, target_filename,
                     stored_sha256, computed_sha256, integrity_verified, checked_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, checkId, runId, batchId, file.targetType(), file.targetId(), file.filename(),
                file.storedSha256(), computed, matches, actorId);
            results.add(new IntegrityCheckResult(checkId.toString(), runId.toString(), file.targetType(),
                file.targetId().toString(), file.filename(), file.storedSha256(), computed, matches,
                authorization.currentUsername(), java.time.Instant.now().toString()));
        }
        audit.record("DEPOSIT_EVIDENCE_INTEGRITY_CHECKED", "DEPOSIT_BATCH", batchId,
            Map.of("checkRunId", runId.toString(), "filesChecked", files.size(),
                "filesVerified", verified, "integrityFailures", files.size() - verified));
        return new IntegrityCheckRun(runId.toString(), files.size(), verified, files.size() - verified, results);
    }

    @GetMapping
    public List<IntegrityCheckResult> history(@PathVariable UUID batchId) {
        BatchScope batch = requireBatch(batchId);
        authorization.requireUnitPermission("FINANCE_VIEW", batch.scopeUnitId());
        return jdbc.query("""
            SELECT c.id, c.check_run_id, c.target_type, c.target_id, c.target_filename,
                   c.stored_sha256, c.computed_sha256, c.integrity_verified, u.username, c.checked_at
            FROM deposit_evidence_integrity_checks c
            JOIN admin_users u ON u.id = c.checked_by
            WHERE c.deposit_batch_id = ?
            ORDER BY c.checked_at DESC, c.id
            """, (rs, row) -> new IntegrityCheckResult(rs.getObject("id", UUID.class).toString(),
                rs.getObject("check_run_id", UUID.class).toString(), rs.getString("target_type"),
                rs.getObject("target_id", UUID.class).toString(), rs.getString("target_filename"),
                rs.getString("stored_sha256"), rs.getString("computed_sha256"),
                rs.getBoolean("integrity_verified"), rs.getString("username"),
                rs.getTimestamp("checked_at").toInstant().toString()), batchId);
    }

    private BatchScope requireBatch(UUID batchId) {
        return jdbc.query("SELECT scope_unit_id FROM deposit_batches WHERE id = ?",
            (rs, row) -> new BatchScope(rs.getObject("scope_unit_id", UUID.class)), batchId)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Deposit batch not found."));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    public record IntegrityCheckRun(String runId, int filesChecked, int filesVerified,
            int integrityFailures, List<IntegrityCheckResult> results) {}
    public record IntegrityCheckResult(String id, String runId, String targetType, String targetId,
            String filename, String storedSha256, String computedSha256, boolean integrityVerified,
            String checkedBy, String checkedAt) {}
    private record StoredFile(String targetType, UUID targetId, String filename,
            String storedSha256, byte[] bytes) {}
    private record BatchScope(UUID scopeUnitId) {}
}
