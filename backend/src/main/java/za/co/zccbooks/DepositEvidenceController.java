package za.co.zccbooks;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/deposit-batches/{batchId}/evidence")
public class DepositEvidenceController {
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public DepositEvidenceController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping
    public List<EvidenceDto> list(@PathVariable UUID batchId) {
        BatchScope batch = requireBatch(batchId);
        authorization.requireUnitPermission("FINANCE_VIEW", batch.scopeUnitId());
        return evidenceRows(batchId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public List<EvidenceDto> upload(@PathVariable UUID batchId,
            @RequestParam("files") List<MultipartFile> files) throws IOException {
        BatchScope batch = requireBatch(batchId);
        authorization.requireUnitPermission("FINANCE_DEPOSIT", batch.scopeUnitId());
        if (!List.of("DRAFT", "SUBMITTED", "PARTIALLY_RECONCILED").contains(batch.status())) {
            throw new ConflictException("Evidence can only be added before a deposit is fully reconciled.");
        }
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("Select at least one evidence file.");
        if (files.size() > 10) throw new IllegalArgumentException("A deposit can receive at most 10 files per upload.");

        UUID actorId = jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
        for (MultipartFile file : files) {
            if (file.isEmpty()) throw new IllegalArgumentException("Empty evidence files are not accepted.");
            if (file.getSize() > MAX_FILE_BYTES) throw new IllegalArgumentException("Each evidence file must be 10 MB or smaller.");
            byte[] bytes = file.getBytes();
            String contentType = detectContentType(bytes);
            String digest = sha256(bytes);
            Integer duplicate = jdbc.queryForObject("""
                SELECT COUNT(*) FROM deposit_evidence
                WHERE deposit_batch_id = ? AND sha256 = ?
                """, Integer.class, batchId, digest);
            if (duplicate != null && duplicate > 0) {
                throw new ConflictException("This exact evidence file has already been attached to the deposit.");
            }
            String filename = safeFilename(file.getOriginalFilename(), contentType);
            UUID evidenceId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO deposit_evidence
                    (id, deposit_batch_id, original_filename, content_type, file_size, sha256, file_data, uploaded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, evidenceId, batchId, filename, contentType, bytes.length, digest, bytes, actorId);
            audit.record("DEPOSIT_EVIDENCE_UPLOADED", "DEPOSIT_EVIDENCE", evidenceId,
                Map.of("depositBatchId", batchId.toString(), "filename", filename,
                    "contentType", contentType, "fileSize", Integer.toString(bytes.length), "sha256", digest));
        }
        return evidenceRows(batchId);
    }

    @GetMapping("/{evidenceId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID batchId, @PathVariable UUID evidenceId) {
        BatchScope batch = requireBatch(batchId);
        authorization.requireUnitPermission("FINANCE_VIEW", batch.scopeUnitId());
        EvidenceContent evidence = jdbc.query("""
            SELECT original_filename, content_type, file_data
            FROM deposit_evidence
            WHERE id = ? AND deposit_batch_id = ?
            """, (rs, n) -> new EvidenceContent(rs.getString("original_filename"),
                rs.getString("content_type"), rs.getBytes("file_data")), evidenceId, batchId)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Deposit evidence not found."));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(evidence.contentType()))
            .contentLength(evidence.bytes().length)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + evidence.filename().replace("\\", "_").replace("\"", "_") + "\"")
            .body(evidence.bytes());
    }

    private List<EvidenceDto> evidenceRows(UUID batchId) {
        return jdbc.query("""
            SELECT id, original_filename, content_type, file_size, sha256, uploaded_at
            FROM deposit_evidence
            WHERE deposit_batch_id = ?
            ORDER BY uploaded_at, id
            """, (rs, n) -> new EvidenceDto(rs.getObject("id", UUID.class).toString(),
                rs.getString("original_filename"), rs.getString("content_type"),
                rs.getLong("file_size"), rs.getString("sha256"),
                rs.getTimestamp("uploaded_at").toInstant().toString()), batchId);
    }

    private BatchScope requireBatch(UUID batchId) {
        return jdbc.query("""
            SELECT scope_unit_id, status FROM deposit_batches WHERE id = ?
            """, (rs, n) -> new BatchScope(rs.getObject("scope_unit_id", UUID.class), rs.getString("status")), batchId)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Deposit batch not found."));
    }

    private static String detectContentType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e
                && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) {
            return "image/png";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        if (bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-') {
            return "application/pdf";
        }
        throw new IllegalArgumentException("Unsupported evidence format. Upload a JPEG, PNG, WebP or PDF file.");
    }

    private static String safeFilename(String original, String contentType) {
        String fallback = contentType.equals("application/pdf") ? "deposit-evidence.pdf" : "deposit-evidence";
        String filename = original == null || original.isBlank() ? fallback : original;
        filename = filename.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (filename.isBlank()) filename = fallback;
        return filename.length() > 255 ? filename.substring(filename.length() - 255) : filename;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    public record EvidenceDto(String id, String filename, String contentType, long fileSize,
            String sha256, String uploadedAt) {}
    private record BatchScope(UUID scopeUnitId, String status) {}
    private record EvidenceContent(String filename, String contentType, byte[] bytes) {}
}
