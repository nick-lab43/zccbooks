package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
public class FinancialJournalController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public FinancialJournalController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/organisations/{organisationId}/finance-accounts")
    public List<FinanceAccountDto> accounts(@PathVariable UUID organisationId) {
        requireOrganisation(organisationId);
        authorization.requireOrganisationOrUnitPermission("FINANCE_JOURNAL_VIEW", organisationId);
        return jdbc.query("""
            SELECT id, organisation_id, account_code, account_name, account_type, currency, active
            FROM finance_accounts WHERE organisation_id = ?
            ORDER BY account_code
            """, (rs, n) -> accountDto(rs), organisationId);
    }

    @PostMapping("/organisations/{organisationId}/finance-accounts")
    @Transactional
    public ResponseEntity<FinanceAccountDto> createAccount(@PathVariable UUID organisationId,
            @Valid @RequestBody CreateFinanceAccount request) {
        requireOrganisation(organisationId);
        authorization.requireOrganisationOrUnitPermission("FINANCE_JOURNAL_CREATE", organisationId);
        String code = request.code().trim().toUpperCase();
        String currency = request.currency().toUpperCase();
        if (exists("SELECT COUNT(*) FROM finance_accounts WHERE organisation_id = ? AND LOWER(account_code) = LOWER(?)",
                organisationId, code)) {
            throw new ConflictException("An account with this code already exists in the organisation.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO finance_accounts
                (id, organisation_id, account_code, account_name, account_type, currency, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, organisationId, code, request.name().trim(), request.accountType(), currency, actorId());
        audit.record("FINANCE_ACCOUNT_CREATED", "FINANCE_ACCOUNT", id,
            Map.of("organisationId", organisationId.toString(), "accountCode", code,
                "accountName", request.name().trim(), "accountType", request.accountType(), "currency", currency));
        return ResponseEntity.status(HttpStatus.CREATED).body(requireAccount(id));
    }

    @PatchMapping("/finance-accounts/{accountId}")
    @Transactional
    public FinanceAccountDto updateAccount(@PathVariable UUID accountId,
            @Valid @RequestBody UpdateFinanceAccount request) {
        FinanceAccountDto existing = requireAccount(accountId);
        authorization.requireOrganisationOrUnitPermission("FINANCE_JOURNAL_CREATE", UUID.fromString(existing.organisationId()));
        if (exists("SELECT COUNT(*) FROM finance_journal_lines WHERE account_id = ?", accountId)
                && !existing.name().equals(request.name().trim())) {
            throw new ConflictException("An account name cannot be changed after it has been used in a journal.");
        }
        jdbc.update("""
            UPDATE finance_accounts SET account_name = ?, active = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, request.name().trim(), request.active(), accountId);
        audit.record("FINANCE_ACCOUNT_UPDATED", "FINANCE_ACCOUNT", accountId,
            Map.of("organisationId", existing.organisationId(), "accountName", request.name().trim(),
                "previousActive", existing.active(), "active", request.active()));
        return requireAccount(accountId);
    }

    @GetMapping("/organisations/{organisationId}/finance-journals")
    public List<FinanceJournalSummaryDto> journals(@PathVariable UUID organisationId,
            @RequestParam(required = false) String status) {
        requireOrganisation(organisationId);
        authorization.requireOrganisationOrUnitPermission("FINANCE_JOURNAL_VIEW", organisationId);
        if (status != null && !List.of("DRAFT", "SUBMITTED", "APPROVED", "POSTED", "REJECTED").contains(status)) {
            throw new IllegalArgumentException("Unsupported journal status.");
        }
        String sql = """
            SELECT j.id, j.scope_unit_id, u.name AS scope_name, j.journal_number, j.journal_date,
                   j.currency, j.description, j.status, j.created_at,
                   COALESCE(SUM(l.debit_amount), 0) AS debit_total
            FROM finance_journals j
            JOIN organisational_units u ON u.id = j.scope_unit_id AND u.organisation_id = j.organisation_id
            LEFT JOIN finance_journal_lines l ON l.journal_id = j.id
            WHERE j.organisation_id = ?
            """;
        if (!authorization.hasOrganisationWidePermission("FINANCE_JOURNAL_VIEW", organisationId)) {
            sql += """
                AND EXISTS (
                    WITH RECURSIVE visible_units AS (
                        SELECT a.scope_unit_id AS id
                        FROM admin_role_assignments a
                        JOIN admin_users au ON au.id = a.user_id
                        JOIN admin_role_permissions rp ON rp.role_id = a.role_id
                        WHERE a.scope_type = 'UNIT' AND a.organisation_id = ?
                          AND a.revoked_at IS NULL AND LOWER(au.username) = LOWER(?)
                          AND rp.permission_code = 'FINANCE_JOURNAL_VIEW'
                        UNION
                        SELECT child.id FROM organisational_units child
                        JOIN visible_units parent ON child.parent_id = parent.id
                        WHERE child.organisation_id = ?
                    )
                    SELECT 1 FROM visible_units v WHERE v.id = j.scope_unit_id
                )
                """;
        }
        if (status != null) sql += " AND j.status = ? ";
        sql += """
            GROUP BY j.id, u.name
            ORDER BY j.journal_date DESC, j.created_at DESC
            """;
        if (!authorization.hasOrganisationWidePermission("FINANCE_JOURNAL_VIEW", organisationId)) {
            if (status != null) {
                return jdbc.query(sql, (rs, n) -> summaryDto(rs), organisationId, organisationId,
                    authorization.currentUsername(), organisationId, status);
            }
            return jdbc.query(sql, (rs, n) -> summaryDto(rs), organisationId, organisationId,
                authorization.currentUsername(), organisationId);
        }
        if (status != null) return jdbc.query(sql, (rs, n) -> summaryDto(rs), organisationId, status);
        return jdbc.query(sql, (rs, n) -> summaryDto(rs), organisationId);
    }

    @GetMapping("/finance-journals/{journalId}")
    public FinanceJournalDto journal(@PathVariable UUID journalId) {
        JournalRow journal = requireJournal(journalId, false);
        authorization.requireUnitPermission("FINANCE_JOURNAL_VIEW", journal.scopeUnitId());
        return journalDto(journalId);
    }

    @PostMapping("/organisations/{organisationId}/finance-journals")
    @Transactional
    public ResponseEntity<FinanceJournalDto> createJournal(@PathVariable UUID organisationId,
            @Valid @RequestBody CreateFinanceJournal request) {
        requireOrganisation(organisationId);
        authorization.requireUnitPermission("FINANCE_JOURNAL_CREATE", request.scopeUnitId());
        requireUnitInOrganisation(request.scopeUnitId(), organisationId);
        String currency = request.currency().toUpperCase();
        String number = request.journalNumber().trim();
        if (exists("SELECT COUNT(*) FROM finance_journals WHERE organisation_id = ? AND journal_number = ?",
                organisationId, number)) {
            throw new ConflictException("A journal with this number already exists in the organisation.");
        }
        String sourceType = request.sourceType() == null ? "MANUAL" : request.sourceType();
        if ("RECONCILIATION_EXCEPTION".equals(sourceType)) {
            if (request.sourceId() == null || !exists("""
                SELECT COUNT(*) FROM reconciliation_exceptions
                WHERE id = ? AND organisation_id = ? AND scope_unit_id = ?
                """, request.sourceId(), organisationId, request.scopeUnitId())) {
                throw new IllegalArgumentException("The linked reconciliation exception must exist in the same organisation and financial scope.");
            }
        } else if (request.sourceId() != null) {
            throw new IllegalArgumentException("A manual journal cannot specify a source record.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO finance_journals
                (id, organisation_id, scope_unit_id, journal_number, journal_date, currency,
                 description, source_type, source_id, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, organisationId, request.scopeUnitId(), number, request.journalDate(), currency,
            request.description().trim(), sourceType, request.sourceId(), actorId());
        for (FinanceJournalLineRequest line : request.lines()) {
            insertLine(id, organisationId, currency, line);
        }
        audit.record("FINANCE_JOURNAL_CREATED", "FINANCE_JOURNAL", id,
            Map.of("organisationId", organisationId.toString(), "scopeUnitId", request.scopeUnitId().toString(),
                "journalNumber", number, "currency", currency, "lineCount", request.lines().size()));
        return ResponseEntity.status(HttpStatus.CREATED).body(journalDto(id));
    }

    @PutMapping("/finance-journals/{journalId}/lines")
    @Transactional
    public FinanceJournalDto replaceDraftLines(@PathVariable UUID journalId,
            @Valid @RequestBody ReplaceFinanceJournalLines request) {
        JournalRow journal = requireJournal(journalId, true);
        authorization.requireUnitPermission("FINANCE_JOURNAL_CREATE", journal.scopeUnitId());
        if (!"DRAFT".equals(journal.status())) throw new ConflictException("Only draft journals can be edited.");
        jdbc.update("DELETE FROM finance_journal_lines WHERE journal_id = ?", journalId);
        for (FinanceJournalLineRequest line : request.lines()) {
            insertLine(journalId, journal.organisationId(), journal.currency(), line);
        }
        audit.record("FINANCE_JOURNAL_LINES_REPLACED", "FINANCE_JOURNAL", journalId,
            Map.of("organisationId", journal.organisationId().toString(), "lineCount", request.lines().size()));
        return journalDto(journalId);
    }

    @PostMapping("/finance-journals/{journalId}/submit")
    @Transactional
    public FinanceJournalDto submit(@PathVariable UUID journalId) {
        JournalRow journal = requireJournal(journalId, true);
        authorization.requireUnitPermission("FINANCE_JOURNAL_CREATE", journal.scopeUnitId());
        if (!"DRAFT".equals(journal.status())) throw new ConflictException("Only draft journals can be submitted.");
        validateBalanced(journalId, journal.organisationId(), journal.currency());
        jdbc.update("""
            UPDATE finance_journals SET status = 'SUBMITTED', submitted_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, journalId);
        audit.record("FINANCE_JOURNAL_SUBMITTED", "FINANCE_JOURNAL", journalId,
            Map.of("organisationId", journal.organisationId().toString(), "scopeUnitId", journal.scopeUnitId().toString()));
        return journalDto(journalId);
    }

    @PostMapping("/finance-journals/{journalId}/approve")
    @Transactional
    public FinanceJournalDto approve(@PathVariable UUID journalId) {
        JournalRow journal = requireJournal(journalId, true);
        authorization.requireUnitPermission("FINANCE_JOURNAL_APPROVE", journal.scopeUnitId());
        if (!"SUBMITTED".equals(journal.status())) throw new ConflictException("Only submitted journals can be approved.");
        UUID actor = actorId();
        if (actor.equals(journal.createdBy())) throw new ConflictException("The journal creator cannot approve their own journal.");
        jdbc.update("""
            UPDATE finance_journals
            SET status = 'APPROVED', approved_by = ?, approved_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, actor, journalId);
        audit.record("FINANCE_JOURNAL_APPROVED", "FINANCE_JOURNAL", journalId,
            Map.of("organisationId", journal.organisationId().toString(), "scopeUnitId", journal.scopeUnitId().toString()));
        return journalDto(journalId);
    }

    @PostMapping("/finance-journals/{journalId}/reject")
    @Transactional
    public FinanceJournalDto reject(@PathVariable UUID journalId, @Valid @RequestBody RejectFinanceJournal request) {
        JournalRow journal = requireJournal(journalId, true);
        authorization.requireUnitPermission("FINANCE_JOURNAL_APPROVE", journal.scopeUnitId());
        if (!"SUBMITTED".equals(journal.status())) throw new ConflictException("Only submitted journals can be rejected.");
        UUID actor = actorId();
        if (actor.equals(journal.createdBy())) throw new ConflictException("The journal creator cannot reject their own journal.");
        jdbc.update("UPDATE finance_journals SET status = 'REJECTED' WHERE id = ?", journalId);
        audit.record("FINANCE_JOURNAL_REJECTED", "FINANCE_JOURNAL", journalId,
            Map.of("organisationId", journal.organisationId().toString(), "scopeUnitId", journal.scopeUnitId().toString(),
                "reason", request.reason().trim()));
        return journalDto(journalId);
    }

    @PostMapping("/finance-journals/{journalId}/post")
    @Transactional
    public FinanceJournalDto post(@PathVariable UUID journalId) {
        JournalRow journal = requireJournal(journalId, true);
        authorization.requireUnitPermission("FINANCE_JOURNAL_APPROVE", journal.scopeUnitId());
        if (!"APPROVED".equals(journal.status())) throw new ConflictException("Only approved journals can be posted.");
        UUID actor = actorId();
        if (actor.equals(journal.approvedBy()) || actor.equals(journal.createdBy())) {
            throw new ConflictException("The journal creator and approver cannot post the same journal.");
        }
        jdbc.update("""
            UPDATE finance_journals
            SET status = 'POSTED', posted_by = ?, posted_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, actor, journalId);
        audit.record("FINANCE_JOURNAL_POSTED", "FINANCE_JOURNAL", journalId,
            Map.of("organisationId", journal.organisationId().toString(), "scopeUnitId", journal.scopeUnitId().toString(),
                "journalNumber", journal.journalNumber()));
        return journalDto(journalId);
    }

    private void insertLine(UUID journalId, UUID organisationId, String currency, FinanceJournalLineRequest line) {
        Integer validAccount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM finance_accounts
            WHERE id = ? AND organisation_id = ? AND currency = ? AND active = TRUE
            """, Integer.class, line.accountId(), organisationId, currency);
        if (validAccount == null || validAccount == 0) {
            throw new IllegalArgumentException("Every journal line must reference an active account in the same organisation and currency.");
        }
        jdbc.update("""
            INSERT INTO finance_journal_lines
                (id, journal_id, account_id, organisation_id, currency, debit_amount, credit_amount, description)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), journalId, line.accountId(), organisationId, currency,
            line.debitAmount(), line.creditAmount(), blankToNull(line.description()));
    }

    private void validateBalanced(UUID journalId, UUID organisationId, String currency) {
        JournalTotals totals = jdbc.queryForObject("""
            SELECT COUNT(*) AS line_count, COALESCE(SUM(debit_amount), 0) AS debit_total,
                   COALESCE(SUM(credit_amount), 0) AS credit_total
            FROM finance_journal_lines
            WHERE journal_id = ? AND organisation_id = ? AND currency = ?
            """, (rs, n) -> new JournalTotals(rs.getLong("line_count"),
                rs.getBigDecimal("debit_total"), rs.getBigDecimal("credit_total")),
            journalId, organisationId, currency);
        boolean inactiveAccount = exists("""
            SELECT COUNT(*) FROM finance_journal_lines l
            JOIN finance_accounts a ON a.id = l.account_id
            WHERE l.journal_id = ? AND a.active = FALSE
            """, journalId);
        if (totals == null || totals.lineCount() < 2 || totals.debitTotal().signum() <= 0
                || totals.debitTotal().compareTo(totals.creditTotal()) != 0 || inactiveAccount) {
            throw new ConflictException("A journal requires at least two lines and equal positive debit and credit totals before submission.");
        }
    }

    private FinanceJournalDto journalDto(UUID id) {
        JournalRow j = requireJournal(id, false);
        List<FinanceJournalLineDto> lines = jdbc.query("""
            SELECT l.id, l.account_id, a.account_code, a.account_name, l.debit_amount,
                   l.credit_amount, l.description
            FROM finance_journal_lines l
            JOIN finance_accounts a ON a.id = l.account_id
            WHERE l.journal_id = ?
            ORDER BY a.account_code, l.id
            """, (rs, n) -> new FinanceJournalLineDto(rs.getObject("id", UUID.class).toString(),
                rs.getObject("account_id", UUID.class).toString(), rs.getString("account_code"),
                rs.getString("account_name"), rs.getBigDecimal("debit_amount"),
                rs.getBigDecimal("credit_amount"), rs.getString("description")), id);
        return new FinanceJournalDto(j.id().toString(), j.organisationId().toString(),
            j.scopeUnitId().toString(), j.journalNumber(), j.journalDate(), j.currency(),
            j.description(), j.status(), j.createdBy().toString(),
            j.approvedBy() == null ? null : j.approvedBy().toString(),
            j.postedBy() == null ? null : j.postedBy().toString(), lines);
    }

    private JournalRow requireJournal(UUID id, boolean lock) {
        String sql = """
            SELECT id, organisation_id, scope_unit_id, journal_number, journal_date, currency,
                   description, status, created_by, approved_by, posted_by
            FROM finance_journals WHERE id = ?
            """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, n) -> new JournalRow(rs.getObject("id", UUID.class),
            rs.getObject("organisation_id", UUID.class), rs.getObject("scope_unit_id", UUID.class),
            rs.getString("journal_number"), rs.getObject("journal_date", LocalDate.class),
            rs.getString("currency"), rs.getString("description"), rs.getString("status"),
            rs.getObject("created_by", UUID.class), rs.getObject("approved_by", UUID.class),
            rs.getObject("posted_by", UUID.class)),
            id).stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Financial journal not found."));
    }

    private FinanceAccountDto requireAccount(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, account_code, account_name, account_type, currency, active
            FROM finance_accounts WHERE id = ?
            """, (rs, n) -> new FinanceAccountDto(rs.getObject("id", UUID.class).toString(),
                rs.getObject("organisation_id", UUID.class).toString(), rs.getString("account_code"),
                rs.getString("account_name"), rs.getString("account_type"), rs.getString("currency"),
                rs.getBoolean("active")), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Financial account not found."));
    }

    private FinanceAccountDto accountDto(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FinanceAccountDto(rs.getObject("id", UUID.class).toString(),
            rs.getObject("organisation_id", UUID.class).toString(),
            rs.getString("account_code"), rs.getString("account_name"), rs.getString("account_type"),
            rs.getString("currency"), rs.getBoolean("active"));
    }

    private FinanceJournalSummaryDto summaryDto(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FinanceJournalSummaryDto(rs.getObject("id", UUID.class).toString(),
            rs.getObject("scope_unit_id", UUID.class).toString(), rs.getString("scope_name"),
            rs.getString("journal_number"), rs.getObject("journal_date", LocalDate.class),
            rs.getString("currency"), rs.getString("description"), rs.getString("status"),
            rs.getBigDecimal("debit_total"));
    }

    private void requireOrganisation(UUID id) {
        if (!exists("SELECT COUNT(*) FROM organisations WHERE id = ?", id)) {
            throw new ResourceNotFoundException("Organisation not found.");
        }
    }

    private void requireUnitInOrganisation(UUID unitId, UUID organisationId) {
        if (!exists("SELECT COUNT(*) FROM organisational_units WHERE id = ? AND organisation_id = ? AND active = TRUE",
                unitId, organisationId)) {
            throw new ResourceNotFoundException("Active geographical unit not found in this organisation.");
        }
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private UUID actorId() {
        return jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreateFinanceAccount(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Pattern(regexp = "ASSET|LIABILITY|EQUITY|INCOME|EXPENSE") String accountType,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency
    ) {}

    public record UpdateFinanceAccount(@NotBlank @Size(max = 120) String name, @NotNull Boolean active) {}

    public record FinanceAccountDto(String id, String organisationId, String code, String name,
        String accountType, String currency, boolean active) {}

    public record FinanceJournalLineRequest(
        @NotNull UUID accountId,
        @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal debitAmount,
        @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal creditAmount,
        @Size(max = 500) String description
    ) {}

    public record CreateFinanceJournal(
        @NotBlank @Size(max = 80) String journalNumber,
        @NotNull UUID scopeUnitId,
        @NotNull LocalDate journalDate,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
        @NotBlank @Size(max = 1000) String description,
        @Pattern(regexp = "MANUAL|RECONCILIATION_EXCEPTION") String sourceType,
        UUID sourceId,
        @NotEmpty @Size(min = 2, max = 200) List<@Valid FinanceJournalLineRequest> lines
    ) {}

    public record ReplaceFinanceJournalLines(
        @NotEmpty @Size(min = 2, max = 200) List<@Valid FinanceJournalLineRequest> lines
    ) {}

    public record RejectFinanceJournal(@NotBlank @Size(max = 1000) String reason) {}

    public record FinanceJournalLineDto(String id, String accountId, String accountCode,
        String accountName, BigDecimal debitAmount, BigDecimal creditAmount, String description) {}

    public record FinanceJournalDto(String id, String organisationId, String scopeUnitId,
        String journalNumber, LocalDate journalDate, String currency, String description,
        String status, String createdBy, String approvedBy, String postedBy,
        List<FinanceJournalLineDto> lines) {}

    public record FinanceJournalSummaryDto(String id, String scopeUnitId, String scopeName,
        String journalNumber, LocalDate journalDate, String currency, String description,
        String status, BigDecimal debitTotal) {}

    private record JournalRow(UUID id, UUID organisationId, UUID scopeUnitId, String journalNumber,
        LocalDate journalDate, String currency, String description, String status,
        UUID createdBy, UUID approvedBy, UUID postedBy) {}
    private record JournalTotals(long lineCount, BigDecimal debitTotal, BigDecimal creditTotal) {}
}
