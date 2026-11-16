package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ReconciliationExceptionController {
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ROWS = 10000;

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public ReconciliationExceptionController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @PostMapping(value = "/organisations/{organisationId}/bank-statement-imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ImportResult importStatement(@PathVariable UUID organisationId, @RequestParam UUID scopeUnitId,
            @RequestPart("file") MultipartFile file) throws IOException {
        authorization.requireUnitPermission("FINANCE_RECONCILE", scopeUnitId);
        if (file.isEmpty() || file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Choose a non-empty CSV file no larger than 5 MB.");
        }
        Integer validScope = jdbc.queryForObject("""
            SELECT COUNT(*) FROM organisational_units WHERE id = ? AND organisation_id = ? AND active = TRUE
            """, Integer.class, scopeUnitId, organisationId);
        if (validScope == null || validScope == 0) throw new ResourceNotFoundException("Active financial scope not found.");

        byte[] bytes = file.getBytes();
        String hash = sha256(bytes);
        Integer previous = jdbc.queryForObject("""
            SELECT COUNT(*) FROM bank_statement_imports
            WHERE organisation_id = ? AND scope_unit_id = ? AND file_sha256 = ?
            """, Integer.class, organisationId, scopeUnitId, hash);
        if (previous != null && previous > 0) throw new ConflictException("This exact statement file has already been imported for this scope.");

        List<List<String>> rows = parseCsv(new String(bytes, StandardCharsets.UTF_8));
        if (rows.size() < 2) throw new IllegalArgumentException("The CSV must contain a header and at least one transaction row.");
        if (rows.size() - 1 > MAX_ROWS) throw new IllegalArgumentException("A statement import cannot exceed 10,000 transaction rows.");
        List<String> header = rows.get(0).stream().map(v -> v.trim().toLowerCase(Locale.ROOT)).toList();
        int dateColumn = column(header, "date");
        int referenceColumn = column(header, "reference");
        int amountColumn = column(header, "amount");
        int descriptionColumn = header.indexOf("description");
        int typeColumn = header.indexOf("type");
        if (dateColumn < 0 || referenceColumn < 0 || amountColumn < 0) {
            throw new IllegalArgumentException("CSV headers must include date, reference and amount. description and type are optional.");
        }

        UUID actor = actorId();
        UUID importId = UUID.randomUUID();
        String fileName = safeFileName(file.getOriginalFilename());
        jdbc.update("""
            INSERT INTO bank_statement_imports
                (id, organisation_id, scope_unit_id, file_name, file_sha256, imported_by)
            VALUES (?, ?, ?, ?, ?, ?)
            """, importId, organisationId, scopeUnitId, fileName, hash, actor);

        int imported = 0;
        int duplicates = 0;
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.stream().allMatch(String::isBlank)) continue;
            if (row.size() <= Math.max(dateColumn, Math.max(referenceColumn, amountColumn))) {
                throw new IllegalArgumentException("CSV row " + (index + 1) + " is missing required values.");
            }
            LocalDate date;
            BigDecimal signedAmount;
            try {
                date = LocalDate.parse(row.get(dateColumn).trim());
                signedAmount = new BigDecimal(row.get(amountColumn).trim().replace(",", ""));
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("CSV row " + (index + 1) + " has an invalid ISO date or amount.");
            }
            String reference = row.get(referenceColumn).trim();
            if (reference.isBlank() || reference.length() > 120 || signedAmount.signum() == 0) {
                throw new IllegalArgumentException("CSV row " + (index + 1) + " needs a reference (max 120 characters) and a non-zero amount.");
            }
            String entryType = signedAmount.signum() < 0 ? "DEBIT" : "CREDIT";
            if (typeColumn >= 0 && row.size() > typeColumn && !row.get(typeColumn).isBlank()) {
                String suppliedType = row.get(typeColumn).trim().toUpperCase(Locale.ROOT);
                if (!suppliedType.equals("CREDIT") && !suppliedType.equals("DEBIT")) {
                    throw new IllegalArgumentException("CSV row " + (index + 1) + " type must be CREDIT or DEBIT.");
                }
                entryType = suppliedType;
            }
            BigDecimal amount = signedAmount.abs();
            if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM bank_statement_entries
                WHERE organisation_id = ? AND scope_unit_id = ? AND transaction_date = ?
                  AND bank_reference = ? AND amount = ?
                """, Integer.class, organisationId, scopeUnitId, date, reference, amount) > 0) {
                duplicates++;
                continue;
            }
            String description = descriptionColumn >= 0 && row.size() > descriptionColumn
                ? blankToNull(row.get(descriptionColumn)) : null;
            jdbc.update("""
                INSERT INTO bank_statement_entries
                    (id, organisation_id, scope_unit_id, transaction_date, bank_reference, amount,
                     description, recorded_by, entry_type, import_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), organisationId, scopeUnitId, date, reference, amount,
                description, actor, entryType, importId);
            imported++;
        }
        jdbc.update("UPDATE bank_statement_imports SET imported_rows = ?, duplicate_rows = ? WHERE id = ?",
            imported, duplicates, importId);
        audit.record("BANK_STATEMENT_IMPORTED", "BANK_STATEMENT_IMPORT", importId,
            Map.of("scopeUnitId", scopeUnitId.toString(), "fileName", fileName,
                "importedRows", Integer.toString(imported), "duplicateRows", Integer.toString(duplicates)));
        return new ImportResult(importId.toString(), fileName, imported, duplicates);
    }

    @PostMapping("/organisations/{organisationId}/reconciliation-exceptions/scan")
    @Transactional
    public ScanResult scan(@PathVariable UUID organisationId, @RequestParam UUID scopeUnitId) {
        authorization.requireUnitPermission("FINANCE_EXCEPTION", scopeUnitId);
        int created = 0;
        List<ExceptionCandidate> deposits = jdbc.query("""
            SELECT b.id, b.bank_reference, b.expected_amount, b.deposit_date,
                   CASE WHEN EXISTS (
                       SELECT 1 FROM bank_statement_entries e
                       WHERE e.organisation_id = b.organisation_id AND e.scope_unit_id = b.scope_unit_id
                         AND LOWER(e.bank_reference) = LOWER(b.bank_reference)
                         AND e.entry_type = 'CREDIT'
                         AND e.transaction_date BETWEEN b.deposit_date - 3 AND b.deposit_date + 3
                         AND e.amount = b.expected_amount
                   ) THEN 'EXACT'
                   WHEN EXISTS (
                       SELECT 1 FROM bank_statement_entries e
                       WHERE e.organisation_id = b.organisation_id AND e.scope_unit_id = b.scope_unit_id
                         AND LOWER(e.bank_reference) = LOWER(b.bank_reference) AND e.entry_type = 'CREDIT'
                   ) THEN 'AMOUNT'
                   ELSE 'MISSING' END AS match_state
            FROM deposit_batches b
            LEFT JOIN deposit_reconciliations r ON r.deposit_batch_id = b.id
            WHERE b.organisation_id = ? AND b.scope_unit_id = ? AND b.status = 'SUBMITTED'
              AND r.id IS NULL
            """, (rs, n) -> new ExceptionCandidate(rs.getObject("id", UUID.class),
                rs.getString("bank_reference"), rs.getBigDecimal("expected_amount"), rs.getString("match_state")),
            organisationId, scopeUnitId);
        for (ExceptionCandidate item : deposits) {
            if ("EXACT".equals(item.matchState())) continue;
            String type = "AMOUNT".equals(item.matchState()) ? "DEPOSIT_AMOUNT_MISMATCH" : "DEPOSIT_NOT_FOUND";
            String summary = "Deposit " + item.reference() + " for " + item.amount().toPlainString()
                + ("AMOUNT".equals(item.matchState()) ? " has a bank credit with a different amount." : " has no matching bank credit.");
            created += insertException(organisationId, scopeUnitId, "DEPOSIT_BATCH", item.id(), type, summary);
        }

        List<StatementCandidate> entries = jdbc.query("""
            SELECT e.id, e.bank_reference, e.amount, e.entry_type,
                   EXISTS (
                       SELECT 1 FROM deposit_batches b
                       WHERE b.organisation_id = e.organisation_id AND b.scope_unit_id = e.scope_unit_id
                         AND b.status IN ('SUBMITTED', 'RECONCILED')
                         AND LOWER(b.bank_reference) = LOWER(e.bank_reference)
                   ) AS reference_exists
            FROM bank_statement_entries e
            LEFT JOIN deposit_reconciliations r ON r.statement_entry_id = e.id
            WHERE e.organisation_id = ? AND e.scope_unit_id = ? AND r.id IS NULL
            """, (rs, n) -> new StatementCandidate(rs.getObject("id", UUID.class),
                rs.getString("bank_reference"), rs.getBigDecimal("amount"), rs.getString("entry_type"),
                rs.getBoolean("reference_exists")), organisationId, scopeUnitId);
        for (StatementCandidate item : entries) {
            String type;
            String summary;
            if ("DEBIT".equals(item.entryType())) {
                type = "BANK_DEBIT_REVIEW";
                summary = "Bank debit " + item.reference() + " for " + item.amount().toPlainString()
                    + " requires classification (for example, bank fee or other withdrawal).";
            } else if (item.referenceExists()) {
                type = "UNMATCHED_BANK_CREDIT";
                summary = "Bank credit " + item.reference() + " for " + item.amount().toPlainString()
                    + " does not match the submitted deposit amount or date.";
            } else {
                type = "UNMATCHED_BANK_CREDIT";
                summary = "Bank credit " + item.reference() + " for " + item.amount().toPlainString()
                    + " has no submitted deposit batch.";
            }
            created += insertException(organisationId, scopeUnitId, "STATEMENT_ENTRY", item.id(), type, summary);
        }
        audit.record("RECONCILIATION_EXCEPTION_SCAN", "FINANCIAL_SCOPE", scopeUnitId,
            Map.of("organisationId", organisationId.toString(), "newExceptions", Integer.toString(created)));
        return new ScanResult(created);
    }

    @GetMapping("/organisations/{organisationId}/reconciliation-exceptions")
    public List<ReconciliationExceptionDto> exceptions(@PathVariable UUID organisationId,
            @RequestParam UUID scopeUnitId, @RequestParam(defaultValue = "false") boolean includeResolved) {
        authorization.requireUnitPermission("FINANCE_VIEW", scopeUnitId);
        return jdbc.query("""
            SELECT x.id, x.source_type, x.source_id, x.exception_type, x.status, x.summary,
                   x.resolution_note, x.created_at, x.updated_at, u.name AS scope_name
            FROM reconciliation_exceptions x
            JOIN organisational_units u ON u.id = x.scope_unit_id
            WHERE x.organisation_id = ? AND x.scope_unit_id = ?
              AND (? = TRUE OR x.status <> 'RESOLVED')
            ORDER BY CASE x.status WHEN 'OPEN' THEN 0 WHEN 'UNDER_REVIEW' THEN 1 ELSE 2 END,
                     x.created_at DESC
            """, (rs, n) -> new ReconciliationExceptionDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("source_type"),
                rs.getObject("source_id", UUID.class).toString(), rs.getString("exception_type"),
                rs.getString("status"), rs.getString("summary"), rs.getString("resolution_note"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("updated_at").toInstant().toString(), rs.getString("scope_name")),
            organisationId, scopeUnitId, includeResolved);
    }

    @PatchMapping("/reconciliation-exceptions/{exceptionId}")
    @Transactional
    public ReconciliationExceptionDto updateException(@PathVariable UUID exceptionId,
            @Valid @RequestBody UpdateException request) {
        ExceptionScope scope = jdbc.query("""
            SELECT organisation_id, scope_unit_id, status
            FROM reconciliation_exceptions WHERE id = ? FOR UPDATE
            """, (rs, n) -> new ExceptionScope(rs.getObject("organisation_id", UUID.class),
                rs.getObject("scope_unit_id", UUID.class), rs.getString("status")), exceptionId)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Reconciliation exception not found."));
        authorization.requireUnitPermission("FINANCE_EXCEPTION", scope.scopeUnitId());
        if ("RESOLVED".equals(scope.status())) throw new ConflictException("A resolved exception cannot be changed.");
        if ("RESOLVED".equals(request.status()) && (request.resolutionNote() == null || request.resolutionNote().isBlank())) {
            throw new IllegalArgumentException("A resolution note is required before resolving an exception.");
        }
        UUID actor = actorId();
        jdbc.update("""
            UPDATE reconciliation_exceptions
            SET status = ?, resolution_note = ?, updated_at = CURRENT_TIMESTAMP, updated_by = ?
            WHERE id = ?
            """, request.status(), blankToNull(request.resolutionNote()), actor, exceptionId);
        audit.record("RECONCILIATION_EXCEPTION_UPDATED", "RECONCILIATION_EXCEPTION", exceptionId,
            Map.of("previousStatus", scope.status(), "status", request.status(),
                "scopeUnitId", scope.scopeUnitId().toString()));
        return jdbc.query("""
            SELECT x.id, x.source_type, x.source_id, x.exception_type, x.status, x.summary,
                   x.resolution_note, x.created_at, x.updated_at, u.name AS scope_name
            FROM reconciliation_exceptions x JOIN organisational_units u ON u.id = x.scope_unit_id
            WHERE x.id = ?
            """, (rs, n) -> new ReconciliationExceptionDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("source_type"),
                rs.getObject("source_id", UUID.class).toString(), rs.getString("exception_type"),
                rs.getString("status"), rs.getString("summary"), rs.getString("resolution_note"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("updated_at").toInstant().toString(), rs.getString("scope_name")), exceptionId).get(0);
    }

    private int insertException(UUID organisationId, UUID scopeUnitId, String sourceType, UUID sourceId,
            String exceptionType, String summary) {
        return jdbc.update("""
            INSERT INTO reconciliation_exceptions
                (id, organisation_id, scope_unit_id, source_type, source_id, exception_type, summary)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source_type, source_id, exception_type) DO NOTHING
            """, UUID.randomUUID(), organisationId, scopeUnitId, sourceType, sourceId, exceptionType, summary);
    }

    private UUID actorId() {
        return jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
    }

    private static int column(List<String> header, String name) { return header.indexOf(name); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String safeFileName(String name) {
        if (name == null || name.isBlank()) return "bank-statement.csv";
        String safe = name.replace('\\', '/');
        safe = safe.substring(safe.lastIndexOf('/') + 1);
        return safe.length() > 255 ? safe.substring(safe.length() - 255) : safe;
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable.", ex); }
    }

    private static List<List<String>> parseCsv(String source) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < source.length() && source.charAt(i + 1) == '"') {
                    field.append('"'); i++;
                } else if (c == '"') quoted = false;
                else field.append(c);
            } else if (c == '"' && field.isEmpty()) quoted = true;
            else if (c == ',') { row.add(field.toString()); field.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') i++;
                row.add(field.toString()); field.setLength(0);
                if (!(row.size() == 1 && row.get(0).isBlank())) rows.add(row);
                row = new ArrayList<>();
            } else field.append(c);
        }
        if (quoted) throw new IllegalArgumentException("The CSV contains an unterminated quoted field.");
        if (!row.isEmpty() || !field.isEmpty()) { row.add(field.toString()); rows.add(row); }
        if (!rows.isEmpty() && rows.get(0).get(0).startsWith("\uFEFF")) {
            rows.get(0).set(0, rows.get(0).get(0).substring(1));
        }
        return rows;
    }

    public record ImportResult(String importId, String fileName, int importedRows, int duplicateRows) {}
    public record ScanResult(int newExceptions) {}
    public record ReconciliationExceptionDto(String id, String sourceType, String sourceId,
        String exceptionType, String status, String summary, String resolutionNote,
        String createdAt, String updatedAt, String scopeName) {}
    public record UpdateException(@NotBlank @Pattern(regexp = "UNDER_REVIEW|RESOLVED") String status,
        @Size(max = 1000) String resolutionNote) {}
    private record ExceptionCandidate(UUID id, String reference, BigDecimal amount, String matchState) {}
    private record StatementCandidate(UUID id, String reference, BigDecimal amount, String entryType, boolean referenceExists) {}
    private record ExceptionScope(UUID organisationId, UUID scopeUnitId, String status) {}
}
