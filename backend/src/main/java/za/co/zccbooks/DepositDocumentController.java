package za.co.zccbooks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.awt.image.BufferedImage;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.util.Iterator;
import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/deposit-batches/{batchId}/documents")
public class DepositDocumentController {
    private static final int MAX_PAGE_BYTES = 10 * 1024 * 1024;
    private static final long MAX_PDF_BYTES = 100L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public DepositDocumentController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping
    public List<DocumentDto> list(@PathVariable UUID batchId) {
        BatchScope batch = requireBatch(batchId);
        authorization.requireUnitPermission("FINANCE_VIEW", batch.scopeUnitId());
        return documentRows(batchId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public List<DocumentDto> create(@PathVariable UUID batchId,
            @RequestParam("pages") List<MultipartFile> pages,
            @RequestParam("sourceEvidenceIds") List<UUID> sourceEvidenceIds) throws IOException {
        BatchScope batch = requireBatch(batchId);
        authorization.requireUnitPermission("FINANCE_DEPOSIT", batch.scopeUnitId());
        if (!List.of("DRAFT", "SUBMITTED", "PARTIALLY_RECONCILED").contains(batch.status())) {
            throw new ConflictException("Scanned documents can only be created before a deposit is fully reconciled.");
        }
        if (pages == null || pages.isEmpty() || pages.size() > 10 || sourceEvidenceIds == null
                || sourceEvidenceIds.size() != pages.size()) {
            throw new IllegalArgumentException("Select between 1 and 10 source photographs, one per PDF page.");
        }
        if (sourceEvidenceIds.stream().distinct().count() != sourceEvidenceIds.size()) {
            throw new IllegalArgumentException("A source photograph can only be used once in this document.");
        }
        String sourcePlaceholders = String.join(",", java.util.Collections.nCopies(sourceEvidenceIds.size(), "?"));
        List<Object> sourceParameters = new ArrayList<>();
        sourceParameters.add(batchId);
        sourceParameters.addAll(sourceEvidenceIds);
        Integer validSources = jdbc.queryForObject(
            "SELECT COUNT(*) FROM deposit_evidence WHERE deposit_batch_id = ? AND id IN (" + sourcePlaceholders + ")",
            Integer.class, sourceParameters.toArray());
        if (validSources == null || validSources != sourceEvidenceIds.size()) {
            throw new IllegalArgumentException("Every source photograph must belong to this deposit batch.");
        }

        UUID actorId = jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
        byte[] pdfBytes;
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (MultipartFile page : pages) {
                if (page.isEmpty() || page.getSize() > MAX_PAGE_BYTES) {
                    throw new IllegalArgumentException("Each scanned page must be between 1 byte and 10 MB.");
                }
                BufferedImage image = readSafeImage(page);
                PDPage pdfPage = new PDPage(PDRectangle.A4);
                pdf.addPage(pdfPage);
                var embedded = LosslessFactory.createFromImage(pdf, image);
                float margin = 24;
                float availableWidth = PDRectangle.A4.getWidth() - margin * 2;
                float availableHeight = PDRectangle.A4.getHeight() - margin * 2;
                float scale = Math.min(availableWidth / image.getWidth(), availableHeight / image.getHeight());
                float width = image.getWidth() * scale;
                float height = image.getHeight() * scale;
                try (PDPageContentStream stream = new PDPageContentStream(pdf, pdfPage)) {
                    stream.drawImage(embedded, (PDRectangle.A4.getWidth() - width) / 2,
                            (PDRectangle.A4.getHeight() - height) / 2, width, height);
                }
            }
            pdf.save(output);
            pdfBytes = output.toByteArray();
        }
        if (pdfBytes.length > MAX_PDF_BYTES) throw new IllegalArgumentException("The generated PDF exceeds 100 MB.");

        String digest = sha256(pdfBytes);
        UUID documentId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deposit_documents
                (id, deposit_batch_id, original_filename, file_size, sha256, file_data, page_count, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, documentId, batchId, "deposit-scan-" + documentId + ".pdf",
                pdfBytes.length, digest, pdfBytes, pages.size(), actorId);
        for (int i = 0; i < sourceEvidenceIds.size(); i++) {
            jdbc.update("""
                INSERT INTO deposit_document_sources (document_id, evidence_id, page_number)
                VALUES (?, ?, ?)
                """, documentId, sourceEvidenceIds.get(i), i + 1);
        }
        audit.record("DEPOSIT_DOCUMENT_CREATED", "DEPOSIT_DOCUMENT", documentId,
            Map.of("depositBatchId", batchId.toString(), "pageCount", Integer.toString(pages.size()),
                "fileSize", Integer.toString(pdfBytes.length), "sha256", digest));
        return documentRows(batchId);
    }

    @GetMapping("/{documentId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID batchId, @PathVariable UUID documentId) {
        BatchScope batch = requireBatch(batchId);
        authorization.requireUnitPermission("FINANCE_VIEW", batch.scopeUnitId());
        StoredDocument document = jdbc.query("""
            SELECT original_filename, file_data FROM deposit_documents
            WHERE id = ? AND deposit_batch_id = ?
            """, (rs, n) -> new StoredDocument(rs.getString("original_filename"), rs.getBytes("file_data")),
            documentId, batchId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Scanned deposit document not found."));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .contentLength(document.bytes().length).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + document.filename() + "\"")
            .body(document.bytes());
    }

    @GetMapping("/ocr-context")
    public OcrContext ocrContext(@PathVariable UUID batchId) {
        BatchScope batch = requireBatch(batchId);
        authorization.requireUnitPermission("FINANCE_VIEW", batch.scopeUnitId());
        return jdbc.query("""
            SELECT deposit_date, bank_reference, expected_amount
            FROM deposit_batches WHERE id = ?
            """, (rs, n) -> new OcrContext(rs.getDate("deposit_date").toLocalDate().toString(),
                rs.getString("bank_reference"), rs.getBigDecimal("expected_amount")), batchId)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Deposit batch not found."));
    }

    @PostMapping("/ocr-reviews")
    @Transactional
    public OcrReviewDto createOcrReview(@PathVariable UUID batchId, @Valid @RequestBody CreateOcrReview request) {
        BatchScope batch = requireBatch(batchId);
        authorization.requireUnitPermission("FINANCE_DEPOSIT", batch.scopeUnitId());
        if (!request.operatorConfirmedOriginal()) {
            throw new IllegalArgumentException("Confirm that the reviewed values were checked against the original deposit slip.");
        }
        Integer evidenceCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM deposit_evidence WHERE id = ? AND deposit_batch_id = ?
            """, Integer.class, request.evidenceId(), batchId);
        if (evidenceCount == null || evidenceCount == 0) {
            throw new ResourceNotFoundException("Source deposit evidence not found in this batch.");
        }
        OcrContext expected = jdbc.query("""
            SELECT deposit_date, bank_reference, expected_amount
            FROM deposit_batches WHERE id = ?
            """, (rs, n) -> new OcrContext(rs.getDate("deposit_date").toLocalDate().toString(),
                rs.getString("bank_reference"), rs.getBigDecimal("expected_amount")), batchId)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Deposit batch not found."));
        boolean amountMatches = request.reviewedAmount().compareTo(expected.expectedAmount()) == 0;
        boolean referenceMatches = request.reviewedBankReference().trim().equalsIgnoreCase(expected.bankReference().trim());
        boolean dateMatches = request.reviewedDepositDate().toString().equals(expected.depositDate());
        UUID actor = jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
        UUID reviewId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deposit_ocr_reviews
                (id, deposit_batch_id, evidence_id, reviewed_amount, reviewed_bank_reference,
                 reviewed_deposit_date, ocr_amount_candidate, ocr_reference_candidate, ocr_date_candidate,
                 amount_matches, reference_matches, date_matches, operator_confirmed_original, reviewed_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
            """, reviewId, batchId, request.evidenceId(), request.reviewedAmount(),
            request.reviewedBankReference().trim(), request.reviewedDepositDate(),
            blankToNull(request.ocrAmountCandidate()), blankToNull(request.ocrReferenceCandidate()),
            blankToNull(request.ocrDateCandidate()), amountMatches, referenceMatches, dateMatches, actor);
        audit.record("DEPOSIT_OCR_REVIEW_RECORDED", "DEPOSIT_OCR_REVIEW", reviewId,
            Map.of("depositBatchId", batchId.toString(), "evidenceId", request.evidenceId().toString(),
                "amountMatches", amountMatches, "referenceMatches", referenceMatches, "dateMatches", dateMatches));
        return new OcrReviewDto(reviewId.toString(), request.evidenceId().toString(),
            request.reviewedAmount(), request.reviewedBankReference().trim(), request.reviewedDepositDate().toString(),
            amountMatches, referenceMatches, dateMatches, authorization.currentUsername(),
            java.time.Instant.now().toString());
    }

    @GetMapping("/ocr-reviews")
    public List<OcrReviewDto> ocrReviews(@PathVariable UUID batchId) {
        BatchScope batch = requireBatch(batchId);
        authorization.requireUnitPermission("FINANCE_VIEW", batch.scopeUnitId());
        return jdbc.query("""
            SELECT r.id, r.evidence_id, r.reviewed_amount, r.reviewed_bank_reference,
                   r.reviewed_deposit_date, r.amount_matches, r.reference_matches, r.date_matches,
                   u.username, r.reviewed_at
            FROM deposit_ocr_reviews r JOIN admin_users u ON u.id = r.reviewed_by
            WHERE r.deposit_batch_id = ? ORDER BY r.reviewed_at DESC, r.id
            """, (rs, n) -> new OcrReviewDto(rs.getObject("id", UUID.class).toString(),
                rs.getObject("evidence_id", UUID.class).toString(), rs.getBigDecimal("reviewed_amount"),
                rs.getString("reviewed_bank_reference"), rs.getDate("reviewed_deposit_date").toLocalDate().toString(),
                rs.getBoolean("amount_matches"), rs.getBoolean("reference_matches"), rs.getBoolean("date_matches"),
                rs.getString("username"), rs.getTimestamp("reviewed_at").toInstant().toString()), batchId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BufferedImage readSafeImage(MultipartFile page) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(page.getInputStream())) {
            if (input == null) throw new IllegalArgumentException("A scanned page is not a supported image.");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("A scanned page is not a supported image.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 100 || height < 100 || width > 6000 || height > 6000
                        || (long) width * height > 20_000_000L) {
                    throw new IllegalArgumentException("Scanned page dimensions exceed the supported 20-megapixel limit.");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private List<DocumentDto> documentRows(UUID batchId) {
        return jdbc.query("""
            SELECT id, original_filename, file_size, sha256, page_count, created_at
            FROM deposit_documents WHERE deposit_batch_id = ?
            ORDER BY created_at DESC, id
            """, (rs, n) -> new DocumentDto(rs.getObject("id", UUID.class).toString(),
                rs.getString("original_filename"), rs.getLong("file_size"), rs.getString("sha256"),
                rs.getInt("page_count"), rs.getTimestamp("created_at").toInstant().toString()), batchId);
    }

    private BatchScope requireBatch(UUID batchId) {
        return jdbc.query("SELECT scope_unit_id, status FROM deposit_batches WHERE id = ?",
            (rs, n) -> new BatchScope(rs.getObject("scope_unit_id", UUID.class), rs.getString("status")), batchId)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Deposit batch not found."));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    public record DocumentDto(String id, String filename, long fileSize, String sha256, int pageCount, String createdAt) {}
    public record OcrContext(String depositDate, String bankReference, java.math.BigDecimal expectedAmount) {}
    public record CreateOcrReview(@NotNull UUID evidenceId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal reviewedAmount,
        @NotBlank @Size(max = 120) String reviewedBankReference, @NotNull LocalDate reviewedDepositDate,
        @Size(max = 80) String ocrAmountCandidate, @Size(max = 120) String ocrReferenceCandidate,
        @Size(max = 40) String ocrDateCandidate, @NotNull Boolean operatorConfirmedOriginal) {}
    public record OcrReviewDto(String id, String evidenceId, BigDecimal reviewedAmount,
        String reviewedBankReference, String reviewedDepositDate, boolean amountMatches,
        boolean referenceMatches, boolean dateMatches, String reviewedBy, String reviewedAt) {}
    private record BatchScope(UUID scopeUnitId, String status) {}
    private record StoredDocument(String filename, byte[] bytes) {}
}
