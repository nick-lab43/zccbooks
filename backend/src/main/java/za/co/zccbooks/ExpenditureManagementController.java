package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
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
public class ExpenditureManagementController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;
    private final ExpenditurePaymentLedgerJournalService expenditureLedger;

    public ExpenditureManagementController(JdbcTemplate jdbc, AuthorizationService authorization,
            AdminAuditService audit, ExpenditurePaymentLedgerJournalService expenditureLedger) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.expenditureLedger = expenditureLedger;
    }

    @GetMapping("/organisations/{organisationId}/expenditure-units")
    public List<ExpenseUnitDto> units(@PathVariable UUID organisationId) {
        authorization.requireOrganisationOrUnitPermission("FINANCE_EXPENSE_CAPTURE", organisationId);
        String sql = """
            SELECT id, level, code, name FROM organisational_units
            WHERE organisation_id = ? AND active = TRUE
            ORDER BY level, name
            """;
        if (!authorization.hasOrganisationWidePermission("FINANCE_EXPENSE_CAPTURE", organisationId)) {
            sql = """
                WITH RECURSIVE visible_units AS (
                    SELECT a.scope_unit_id AS id
                    FROM admin_role_assignments a
                    JOIN admin_users au ON au.id = a.user_id
                    JOIN admin_roles ar ON ar.id = a.role_id
                    JOIN admin_role_permissions rp ON rp.role_id = ar.id
                    WHERE a.scope_type = 'UNIT' AND a.organisation_id = ?
                      AND a.revoked_at IS NULL AND LOWER(au.username) = LOWER(?)
                      AND rp.permission_code = 'FINANCE_EXPENSE_CAPTURE'
                    UNION
                    SELECT child.id FROM organisational_units child
                    JOIN visible_units parent ON child.parent_id = parent.id
                    WHERE child.organisation_id = ?
                )
                SELECT u.id, u.level, u.code, u.name
                FROM organisational_units u JOIN visible_units v ON v.id = u.id
                WHERE u.organisation_id = ? AND u.active = TRUE
                ORDER BY u.level, u.name
                """;
            return jdbc.query(sql, (rs, n) -> new ExpenseUnitDto(rs.getObject("id", UUID.class).toString(),
                rs.getString("level"), rs.getString("code"), rs.getString("name")),
                organisationId, authorization.currentUsername(), organisationId, organisationId);
        }
        return jdbc.query(sql, (rs, n) -> new ExpenseUnitDto(rs.getObject("id", UUID.class).toString(),
            rs.getString("level"), rs.getString("code"), rs.getString("name")), organisationId);
    }

    @GetMapping("/organisations/{organisationId}/expenditure-categories")
    public List<ExpenseCategoryDto> categories(@PathVariable UUID organisationId) {
        authorization.requireOrganisationOrUnitPermission("FINANCE_EXPENSE_CAPTURE", organisationId);
        return jdbc.query("""
            SELECT id, code, name, description, active
            FROM expenditure_categories
            WHERE organisation_id = ?
            ORDER BY name
            """, (rs, n) -> new ExpenseCategoryDto(rs.getObject("id", UUID.class).toString(),
                rs.getString("code"), rs.getString("name"), rs.getString("description"), rs.getBoolean("active")),
            organisationId);
    }

    @PostMapping("/organisations/{organisationId}/expenditure-categories")
    @Transactional
    public ResponseEntity<ExpenseCategoryDto> createCategory(@PathVariable UUID organisationId,
            @Valid @RequestBody CreateExpenseCategory request) {
        authorization.requireGlobalPermission("FINANCE_EXPENSE_APPROVE");
        String code = request.code().trim().toUpperCase();
        Integer duplicate = jdbc.queryForObject("""
            SELECT COUNT(*) FROM expenditure_categories
            WHERE organisation_id = ? AND LOWER(code) = LOWER(?)
            """, Integer.class, organisationId, code);
        if (duplicate != null && duplicate > 0) throw new ConflictException("An expenditure category with this code already exists.");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO expenditure_categories (id, organisation_id, code, name, description, created_by)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, organisationId, code, request.name().trim(), blankToNull(request.description()), actorId());
        audit.record("EXPENDITURE_CATEGORY_CREATED", "EXPENDITURE_CATEGORY", id,
            Map.of("organisationId", organisationId.toString(), "code", code, "name", request.name().trim()));
        return ResponseEntity.status(HttpStatus.CREATED).body(requireCategory(id));
    }

    @PatchMapping("/expenditure-categories/{categoryId}")
    @Transactional
    public ExpenseCategoryDto updateCategory(@PathVariable UUID categoryId,
            @Valid @RequestBody UpdateExpenseCategory request) {
        authorization.requireGlobalPermission("FINANCE_EXPENSE_APPROVE");
        ExpenseCategoryDto existing = requireCategory(categoryId);
        jdbc.update("""
            UPDATE expenditure_categories SET name = ?, description = ?, active = ?
            WHERE id = ?
            """, request.name().trim(), blankToNull(request.description()), request.active(), categoryId);
        audit.record("EXPENDITURE_CATEGORY_UPDATED", "EXPENDITURE_CATEGORY", categoryId,
            Map.of("previousActive", existing.active(), "active", request.active(), "name", request.name().trim()));
        return requireCategory(categoryId);
    }

    @GetMapping("/organisations/{organisationId}/expenditures")
    public List<ExpenditureDto> expenditures(@PathVariable UUID organisationId, @RequestParam UUID scopeUnitId) {
        authorization.requireUnitPermission("FINANCE_EXPENSE_CAPTURE", scopeUnitId);
        return jdbc.query("""
            SELECT e.id, e.scope_unit_id, u.name AS unit_name, e.category_id, c.name AS category_name,
                   e.expense_date, e.payee, e.description, e.reference, e.amount, e.currency, e.status,
                   e.captured_by, e.created_at, e.submitted_at, e.reviewed_by, e.reviewed_at,
                   e.review_note, e.paid_at, e.payment_reference, e.version, e.voided_by, e.voided_at, e.void_reason
            FROM expenditures e
            JOIN organisational_units u ON u.id = e.scope_unit_id
            JOIN expenditure_categories c ON c.id = e.category_id AND c.organisation_id = e.organisation_id
            WHERE e.organisation_id = ? AND e.scope_unit_id = ?
            ORDER BY e.expense_date DESC, e.created_at DESC
            """, (rs, n) -> expenseDto(rs), organisationId, scopeUnitId);
    }

    @PostMapping("/organisations/{organisationId}/expenditures")
    @Transactional
    public ResponseEntity<ExpenditureDto> create(@PathVariable UUID organisationId,
            @Valid @RequestBody CreateExpenditure request) {
        authorization.requireUnitPermission("FINANCE_EXPENSE_CAPTURE", request.scopeUnitId());
        Integer unitCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM organisational_units
            WHERE id = ? AND organisation_id = ? AND active = TRUE
            """, Integer.class, request.scopeUnitId(), organisationId);
        if (unitCount == null || unitCount == 0) throw new ResourceNotFoundException("Active expenditure scope not found.");
        Integer categoryCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM expenditure_categories
            WHERE id = ? AND organisation_id = ? AND active = TRUE
            """, Integer.class, request.categoryId(), organisationId);
        if (categoryCount == null || categoryCount == 0) throw new ResourceNotFoundException("Active expenditure category not found in this organisation.");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO expenditures
                (id, organisation_id, scope_unit_id, category_id, expense_date, payee,
                 description, reference, amount, currency, captured_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, organisationId, request.scopeUnitId(), request.categoryId(), request.expenseDate(),
            request.payee().trim(), request.description().trim(), blankToNull(request.reference()),
            request.amount(), request.currency().toUpperCase(), actorId());
        audit.record("EXPENDITURE_CREATED", "EXPENDITURE", id,
            Map.of("organisationId", organisationId.toString(), "scopeUnitId", request.scopeUnitId().toString(),
                "categoryId", request.categoryId().toString(), "amount", request.amount().toPlainString(),
                "currency", request.currency().toUpperCase()));
        return ResponseEntity.status(HttpStatus.CREATED).body(requireExpense(id));
    }

    @PatchMapping("/expenditures/{expenseId}")
    @Transactional
    public ExpenditureDto amendDraft(@PathVariable UUID expenseId, @Valid @RequestBody AmendDraftExpenditure request) {
        ExpenseContext expense = requireExpenseContext(expenseId, true);
        authorization.requireUnitPermission("FINANCE_EXPENSE_CAPTURE", expense.scopeUnitId());
        if (!actorId().equals(expense.capturedBy())) throw new org.springframework.security.access.AccessDeniedException("Only the original capturer can amend this draft.");
        if (!"DRAFT".equals(expense.status())) throw new ConflictException("Only draft expenditure records can be amended.");
        if (expense.version() != request.version()) throw new ConflictException("This draft has changed. Reload it before amending.");
        Integer categoryCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM expenditure_categories
            WHERE id = ? AND organisation_id = ? AND active = TRUE
            """, Integer.class, request.categoryId(), expense.organisationId());
        if (categoryCount == null || categoryCount == 0) throw new ResourceNotFoundException("Active expenditure category not found in this organisation.");
        int updated = jdbc.update("""
            UPDATE expenditures
            SET category_id = ?, expense_date = ?, payee = ?, description = ?, reference = ?,
                amount = ?, currency = ?, version = version + 1
            WHERE id = ? AND status = 'DRAFT' AND version = ?
            """, request.categoryId(), request.expenseDate(), request.payee().trim(),
            request.description().trim(), blankToNull(request.reference()), request.amount(),
            request.currency().toUpperCase(), expenseId, request.version());
        if (updated != 1) throw new ConflictException("This draft has changed. Reload it before amending.");
        audit.record("EXPENDITURE_DRAFT_AMENDED", "EXPENDITURE", expenseId,
            Map.of("version", Integer.toString(request.version() + 1), "amount", request.amount().toPlainString(),
                "currency", request.currency().toUpperCase()));
        return requireExpense(expenseId);
    }

    @PostMapping("/expenditures/{expenseId}/void")
    @Transactional
    public ExpenditureDto voidDraft(@PathVariable UUID expenseId, @Valid @RequestBody VoidDraftExpenditure request) {
        ExpenseContext expense = requireExpenseContext(expenseId, true);
        authorization.requireUnitPermission("FINANCE_EXPENSE_CAPTURE", expense.scopeUnitId());
        if (request.reason() == null || request.reason().isBlank()) throw new IllegalArgumentException("A reason is required to withdraw a draft.");
        if (!actorId().equals(expense.capturedBy())) throw new org.springframework.security.access.AccessDeniedException("Only the original capturer can withdraw this draft.");
        if (!"DRAFT".equals(expense.status())) throw new ConflictException("Only draft expenditure records can be withdrawn. Submitted or approved records must follow the review or correction process.");
        int updated = jdbc.update("""
            UPDATE expenditures
            SET status = 'VOIDED', voided_by = ?, voided_at = CURRENT_TIMESTAMP, void_reason = ?, version = version + 1
            WHERE id = ? AND status = 'DRAFT'
            """, actorId(), request.reason().trim(), expenseId);
        if (updated != 1) throw new ConflictException("This draft is no longer available for withdrawal.");
        audit.record("EXPENDITURE_DRAFT_VOIDED", "EXPENDITURE", expenseId,
            Map.of("reason", request.reason().trim(), "amount", expense.amount().toPlainString()));
        return requireExpense(expenseId);
    }

    @PostMapping("/expenditures/{expenseId}/submit")
    @Transactional
    public ExpenditureDto submit(@PathVariable UUID expenseId) {
        ExpenseContext expense = requireExpenseContext(expenseId, true);
        authorization.requireUnitPermission("FINANCE_EXPENSE_CAPTURE", expense.scopeUnitId());
        if (!"DRAFT".equals(expense.status())) throw new ConflictException("Only draft expenditure records can be submitted.");
        jdbc.update("UPDATE expenditures SET status = 'SUBMITTED', submitted_at = CURRENT_TIMESTAMP WHERE id = ?", expenseId);
        audit.record("EXPENDITURE_SUBMITTED", "EXPENDITURE", expenseId, Map.of("amount", expense.amount().toPlainString()));
        return requireExpense(expenseId);
    }

    @PostMapping("/expenditures/{expenseId}/review")
    @Transactional
    public ExpenditureDto review(@PathVariable UUID expenseId, @Valid @RequestBody ReviewExpenditure request) {
        ExpenseContext expense = requireExpenseContext(expenseId, true);
        authorization.requireUnitPermission("FINANCE_EXPENSE_APPROVE", expense.scopeUnitId());
        if (!"SUBMITTED".equals(expense.status())) throw new ConflictException("Only submitted expenditure records can be reviewed.");
        UUID reviewer = actorId();
        if (reviewer.equals(expense.capturedBy())) {
            throw new IllegalArgumentException("The person who captured an expense cannot approve or reject it.");
        }
        if (!List.of("APPROVE", "REJECT").contains(request.decision())) {
            throw new IllegalArgumentException("Review decision must be APPROVE or REJECT.");
        }
        if ("REJECT".equals(request.decision()) && blankToNull(request.note()) == null) {
            throw new IllegalArgumentException("A reason is required when rejecting an expenditure record.");
        }
        String status = "APPROVE".equals(request.decision()) ? "APPROVED" : "REJECTED";
        jdbc.update("""
            UPDATE expenditures
            SET status = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP, review_note = ?
            WHERE id = ?
            """, status, reviewer, blankToNull(request.note()), expenseId);
        audit.record("EXPENDITURE_" + status, "EXPENDITURE", expenseId,
            Map.of("amount", expense.amount().toPlainString(),
                "note", blankToNull(request.note()) == null ? "" : blankToNull(request.note())));
        return requireExpense(expenseId);
    }

    @PostMapping("/expenditures/{expenseId}/pay")
    @Transactional
    public ExpenditureDto pay(@PathVariable UUID expenseId, @Valid @RequestBody PayExpenditure request) {
        ExpenseContext expense = requireExpenseContext(expenseId, true);
        authorization.requireUnitPermission("FINANCE_EXPENSE_APPROVE", expense.scopeUnitId());
        if (!"APPROVED".equals(expense.status())) throw new ConflictException("Only approved expenditure records can be marked as paid.");
        jdbc.update("""
            UPDATE expenditures
            SET status = 'PAID', paid_by = ?, paid_at = CURRENT_TIMESTAMP, payment_reference = ?
            WHERE id = ?
            """, actorId(), request.paymentReference().trim(), expenseId);
        expenditureLedger.createDraftIfMapped(expenseId);
        audit.record("EXPENDITURE_PAID", "EXPENDITURE", expenseId,
            Map.of("paymentReference", request.paymentReference().trim(),
                "amount", expense.amount().toPlainString()));
        return requireExpense(expenseId);
    }

    private ExpenseCategoryDto requireCategory(UUID id) {
        return jdbc.query("""
            SELECT id, code, name, description, active FROM expenditure_categories WHERE id = ?
            """, (rs, n) -> new ExpenseCategoryDto(rs.getObject("id", UUID.class).toString(),
                rs.getString("code"), rs.getString("name"), rs.getString("description"), rs.getBoolean("active")), id)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Expenditure category not found."));
    }

    private ExpenditureDto requireExpense(UUID id) {
        return jdbc.query("""
            SELECT e.id, e.scope_unit_id, u.name AS unit_name, e.category_id, c.name AS category_name,
                   e.expense_date, e.payee, e.description, e.reference, e.amount, e.currency, e.status,
                   e.captured_by, e.created_at, e.submitted_at, e.reviewed_by, e.reviewed_at,
                   e.review_note, e.paid_at, e.payment_reference, e.version, e.voided_by, e.voided_at, e.void_reason
            FROM expenditures e
            JOIN organisational_units u ON u.id = e.scope_unit_id
            JOIN expenditure_categories c ON c.id = e.category_id AND c.organisation_id = e.organisation_id
            WHERE e.id = ?
            """, (rs, n) -> expenseDto(rs), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Expenditure record not found."));
    }

    private ExpenseContext requireExpenseContext(UUID id, boolean lock) {
        String sql = "SELECT id, organisation_id, scope_unit_id, status, amount, captured_by, version FROM expenditures WHERE id = ?"
            + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, n) -> new ExpenseContext(rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
            rs.getObject("scope_unit_id", UUID.class), rs.getString("status"), rs.getBigDecimal("amount"),
            rs.getObject("captured_by", UUID.class), rs.getInt("version")), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Expenditure record not found."));
    }

    private ExpenditureDto expenseDto(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExpenditureDto(rs.getObject("id", UUID.class).toString(),
            rs.getObject("scope_unit_id", UUID.class).toString(), rs.getString("unit_name"),
            rs.getObject("category_id", UUID.class).toString(), rs.getString("category_name"),
            rs.getDate("expense_date").toLocalDate().toString(), rs.getString("payee"),
            rs.getString("description"), rs.getString("reference"), rs.getBigDecimal("amount"),
            rs.getString("currency"), rs.getString("status"), rs.getObject("captured_by", UUID.class).toString(),
            rs.getTimestamp("created_at").toInstant().toString(),
            rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant().toString(),
            rs.getObject("reviewed_by", UUID.class) == null ? null : rs.getObject("reviewed_by", UUID.class).toString(),
            rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant().toString(),
            rs.getString("review_note"),
            rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant().toString(),
            rs.getString("payment_reference"), rs.getInt("version"),
            rs.getObject("voided_by", UUID.class) == null ? null : rs.getObject("voided_by", UUID.class).toString(),
            rs.getTimestamp("voided_at") == null ? null : rs.getTimestamp("voided_at").toInstant().toString(),
            rs.getString("void_reason"));
    }

    private UUID actorId() {
        return jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreateExpenseCategory(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,40}") String code,
        @NotBlank @Size(max = 100) String name, @Size(max = 300) String description) {}
    public record UpdateExpenseCategory(@NotBlank @Size(max = 100) String name,
        @Size(max = 300) String description, boolean active) {}
    public record CreateExpenditure(@NotNull UUID scopeUnitId, @NotNull UUID categoryId,
        @NotNull LocalDate expenseDate, @NotBlank @Size(max = 160) String payee,
        @NotBlank @Size(max = 1000) String description, @Size(max = 120) String reference,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency) {}
    public record AmendDraftExpenditure(@NotNull UUID categoryId, @NotNull LocalDate expenseDate,
        @NotBlank @Size(max = 160) String payee, @NotBlank @Size(max = 1000) String description,
        @Size(max = 120) String reference, @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency, @NotNull @jakarta.validation.constraints.Min(1) Integer version) {}
    public record VoidDraftExpenditure(@NotBlank @Size(max = 500) String reason) {}
    public record ReviewExpenditure(@NotBlank String decision, @Size(max = 500) String note) {}
    public record PayExpenditure(@NotBlank @Size(max = 120) String paymentReference) {}
    public record ExpenseUnitDto(String id, String level, String code, String name) {}
    public record ExpenseCategoryDto(String id, String code, String name, String description, boolean active) {}
    public record ExpenditureDto(String id, String scopeUnitId, String scopeName, String categoryId,
        String categoryName, String expenseDate, String payee, String description, String reference,
        BigDecimal amount, String currency, String status, String capturedBy, String createdAt,
        String submittedAt, String reviewedBy, String reviewedAt, String reviewNote,
        String paidAt, String paymentReference, int version, String voidedBy, String voidedAt, String voidReason) {}
    private record ExpenseContext(UUID id, UUID organisationId, UUID scopeUnitId, String status,
        BigDecimal amount, UUID capturedBy, int version) {}
}
