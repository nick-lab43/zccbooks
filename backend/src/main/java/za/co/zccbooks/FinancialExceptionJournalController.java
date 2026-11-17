package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class FinancialExceptionJournalController {
    private static final List<String> MAPPABLE_CODES =
        List.of("BANK_FEE", "SHORT_DEPOSIT", "OVER_DEPOSIT", "REFUND_OR_REVERSAL");

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public FinancialExceptionJournalController(JdbcTemplate jdbc, AuthorizationService authorization,
            AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/organisations/{organisationId}/finance-exception-account-mappings")
    public List<AccountMappingDto> mappings(@PathVariable UUID organisationId) {
        authorization.requireOrganisationOrUnitPermission("FINANCE_JOURNAL_VIEW", organisationId);
        return jdbc.query("""
            SELECT m.id, m.resolution_code, m.currency, m.debit_account_id,
                   da.account_code AS debit_code, da.account_name AS debit_name,
                   m.credit_account_id, ca.account_code AS credit_code, ca.account_name AS credit_name
            FROM finance_exception_account_mappings m
            JOIN finance_accounts da ON da.id = m.debit_account_id
            JOIN finance_accounts ca ON ca.id = m.credit_account_id
            WHERE m.organisation_id = ?
            ORDER BY m.resolution_code, m.currency
            """, (rs, n) -> new AccountMappingDto(rs.getObject("id", UUID.class).toString(),
                rs.getString("resolution_code"), rs.getString("currency"),
                rs.getObject("debit_account_id", UUID.class).toString(), rs.getString("debit_code"),
                rs.getString("debit_name"), rs.getObject("credit_account_id", UUID.class).toString(),
                rs.getString("credit_code"), rs.getString("credit_name")), organisationId);
    }

    @PutMapping("/organisations/{organisationId}/finance-exception-account-mappings")
    @Transactional
    public AccountMappingDto saveMapping(@PathVariable UUID organisationId,
            @Valid @RequestBody SaveAccountMapping request) {
        authorization.requireOrganisationOrUnitPermission("FINANCE_JOURNAL_CREATE", organisationId);
        String code = request.resolutionCode();
        String currency = request.currency().toUpperCase();
        if (!MAPPABLE_CODES.contains(code)) {
            throw new IllegalArgumentException("This reconciliation classification does not create a financial adjustment journal.");
        }
        if (request.debitAccountId().equals(request.creditAccountId())) {
            throw new IllegalArgumentException("Debit and credit accounts must be different.");
        }
        Integer validAccounts = jdbc.queryForObject("""
            SELECT COUNT(*) FROM finance_accounts
            WHERE organisation_id = ? AND currency = ? AND active = TRUE
              AND id IN (?, ?)
            """, Integer.class, organisationId, currency,
            request.debitAccountId(), request.creditAccountId());
        if (validAccounts == null || validAccounts != 2) {
            throw new IllegalArgumentException("Both mapped accounts must be active accounts in this organisation and currency.");
        }

        UUID actor = actorId();
        UUID id = jdbc.query("""
            INSERT INTO finance_exception_account_mappings
                (id, organisation_id, resolution_code, currency, debit_account_id,
                 credit_account_id, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (organisation_id, resolution_code, currency)
            DO UPDATE SET debit_account_id = EXCLUDED.debit_account_id,
                          credit_account_id = EXCLUDED.credit_account_id,
                          updated_at = CURRENT_TIMESTAMP
            RETURNING id
            """, rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
            UUID.randomUUID(), organisationId, code, currency, request.debitAccountId(),
            request.creditAccountId(), actor);
        audit.record("FINANCE_EXCEPTION_ACCOUNT_MAPPING_SAVED", "FINANCE_EXCEPTION_ACCOUNT_MAPPING", id,
            Map.of("organisationId", organisationId.toString(), "resolutionCode", code,
                "currency", currency, "debitAccountId", request.debitAccountId().toString(),
                "creditAccountId", request.creditAccountId().toString()));
        return mapping(id);
    }

    @PostMapping("/reconciliation-exceptions/{exceptionId}/journal")
    @Transactional
    public ResponseEntity<CreatedJournalDto> createDraftJournal(@PathVariable UUID exceptionId) {
        ExceptionRow exception = jdbc.query("""
            SELECT id, organisation_id, scope_unit_id, status, resolution_code,
                   resolution_amount, resolution_note
            FROM reconciliation_exceptions WHERE id = ? FOR UPDATE
            """, (rs, n) -> new ExceptionRow(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getObject("scope_unit_id", UUID.class),
                rs.getString("status"), rs.getString("resolution_code"),
                rs.getBigDecimal("resolution_amount"), rs.getString("resolution_note")), exceptionId)
            .stream().findFirst().orElseThrow(() ->
                new ResourceNotFoundException("Reconciliation exception not found."));

        authorization.requireUnitPermission("FINANCE_JOURNAL_CREATE", exception.scopeUnitId());
        if (!"UNDER_REVIEW".equals(exception.status())) {
            throw new ConflictException("Classify the exception and move it to UNDER_REVIEW before creating an adjustment journal.");
        }
        if (exception.resolutionCode() == null || exception.resolutionAmount() == null
                || exception.resolutionAmount().signum() <= 0 || exception.resolutionNote() == null
                || exception.resolutionNote().isBlank()) {
            throw new ConflictException("A financial classification, positive amount and supporting note are required.");
        }
        if (!MAPPABLE_CODES.contains(exception.resolutionCode())) {
            throw new IllegalArgumentException("This exception classification does not support an automatic journal draft.");
        }
        if (exists("SELECT COUNT(*) FROM finance_journals WHERE source_type = 'RECONCILIATION_EXCEPTION' AND source_id = ?",
                exceptionId)) {
            throw new ConflictException("An adjustment journal has already been created for this exception.");
        }

        MappingRow mapping = jdbc.query("""
            SELECT debit_account_id, credit_account_id, currency
            FROM finance_exception_account_mappings
            WHERE organisation_id = ? AND resolution_code = ? AND currency = 'ZAR'
            """, (rs, n) -> new MappingRow(rs.getObject("debit_account_id", UUID.class),
                rs.getObject("credit_account_id", UUID.class), rs.getString("currency")),
            exception.organisationId(), exception.resolutionCode()).stream().findFirst()
            .orElseThrow(() -> new ConflictException("Configure an account mapping for this classification before creating a journal."));

        if (!exists("""
            SELECT COUNT(*) FROM finance_accounts
            WHERE id IN (?, ?) AND organisation_id = ? AND currency = ? AND active = TRUE
            """, mapping.debitAccountId(), mapping.creditAccountId(), exception.organisationId(), mapping.currency())) {
            throw new ConflictException("The mapped accounts are no longer active. Update the account mapping.");
        }
        String number = "ADJ-" + LocalDate.now() + "-" +
            UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        UUID journalId = UUID.randomUUID();
        UUID actor = actorId();
        jdbc.update("""
            INSERT INTO finance_journals
                (id, organisation_id, scope_unit_id, journal_number, journal_date, currency,
                 description, source_type, source_id, created_by)
            VALUES (?, ?, ?, ?, CURRENT_DATE, ?, ?, 'RECONCILIATION_EXCEPTION', ?, ?)
            """, journalId, exception.organisationId(), exception.scopeUnitId(), number,
            mapping.currency(), "Adjustment for reconciliation exception " + exceptionId,
            exceptionId, actor);
        jdbc.update("""
            INSERT INTO finance_journal_lines
                (id, journal_id, account_id, organisation_id, currency, debit_amount, credit_amount, description)
            VALUES (?, ?, ?, ?, ?, ?, 0, ?), (?, ?, ?, ?, ?, 0, ?, ?)
            """, UUID.randomUUID(), journalId, mapping.debitAccountId(), exception.organisationId(),
            mapping.currency(), exception.resolutionAmount(), exception.resolutionCode().replace('_', ' ') + " adjustment",
            UUID.randomUUID(), journalId, mapping.creditAccountId(), exception.organisationId(),
            mapping.currency(), exception.resolutionAmount(), "Offset for reconciliation exception " + exceptionId);
        audit.record("FINANCE_JOURNAL_CREATED_FROM_EXCEPTION", "FINANCE_JOURNAL", journalId,
            Map.of("organisationId", exception.organisationId().toString(),
                "scopeUnitId", exception.scopeUnitId().toString(),
                "exceptionId", exceptionId.toString(), "resolutionCode", exception.resolutionCode(),
                "amount", exception.resolutionAmount().toPlainString()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedJournalDto(
            journalId.toString(), number, "DRAFT", exceptionId.toString(), mapping.currency(),
            exception.resolutionAmount()));
    }

    private AccountMappingDto mapping(UUID id) {
        return jdbc.query("""
            SELECT m.id, m.resolution_code, m.currency, m.debit_account_id,
                   da.account_code AS debit_code, da.account_name AS debit_name,
                   m.credit_account_id, ca.account_code AS credit_code, ca.account_name AS credit_name
            FROM finance_exception_account_mappings m
            JOIN finance_accounts da ON da.id = m.debit_account_id
            JOIN finance_accounts ca ON ca.id = m.credit_account_id
            WHERE m.id = ?
            """, (rs, n) -> new AccountMappingDto(rs.getObject("id", UUID.class).toString(),
                rs.getString("resolution_code"), rs.getString("currency"),
                rs.getObject("debit_account_id", UUID.class).toString(), rs.getString("debit_code"),
                rs.getString("debit_name"), rs.getObject("credit_account_id", UUID.class).toString(),
                rs.getString("credit_code"), rs.getString("credit_name")), id).get(0);
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private UUID actorId() {
        return jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
    }

    public record SaveAccountMapping(
        @NotNull @Pattern(regexp = "BANK_FEE|SHORT_DEPOSIT|OVER_DEPOSIT|REFUND_OR_REVERSAL") String resolutionCode,
        @NotNull @Pattern(regexp = "[A-Za-z]{3}") String currency,
        @NotNull UUID debitAccountId,
        @NotNull UUID creditAccountId
    ) {}
    public record AccountMappingDto(String id, String resolutionCode, String currency,
        String debitAccountId, String debitAccountCode, String debitAccountName,
        String creditAccountId, String creditAccountCode, String creditAccountName) {}
    public record CreatedJournalDto(String id, String journalNumber, String status,
        String exceptionId, String currency, BigDecimal amount) {}
    private record ExceptionRow(UUID id, UUID organisationId, UUID scopeUnitId, String status,
        String resolutionCode, BigDecimal resolutionAmount, String resolutionNote) {}
    private record MappingRow(UUID debitAccountId, UUID creditAccountId, String currency) {}
}
