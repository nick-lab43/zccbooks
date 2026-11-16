package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class BudgetManagementController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public BudgetManagementController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/organisations/{organisationId}/expenditure-budgets")
    public List<BudgetDto> budgets(@PathVariable UUID organisationId, @RequestParam UUID scopeUnitId) {
        authorization.requireUnitPermission("FINANCE_VIEW", scopeUnitId);
        return jdbc.query("""
            WITH RECURSIVE visible_units(id) AS (
                SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
                UNION ALL
                SELECT child.id FROM organisational_units child
                JOIN visible_units parent ON child.parent_id = parent.id
                WHERE child.organisation_id = ?
            )
            SELECT b.id, b.organisation_id, b.unit_id, u.level AS unit_level, u.code AS unit_code,
                   u.name AS unit_name, b.category_id, c.code AS category_code, c.name AS category_name,
                   b.period_start, b.period_end, b.budget_amount, b.currency, b.note, b.version,
                   b.created_at, b.updated_at,
                   COALESCE((
                       WITH RECURSIVE budget_scope(id) AS (
                           SELECT b.unit_id
                           UNION ALL
                           SELECT child.id FROM organisational_units child
                           JOIN budget_scope parent ON child.parent_id = parent.id
                           WHERE child.organisation_id = b.organisation_id
                       )
                       SELECT SUM(e.amount) FROM expenditures e
                       JOIN budget_scope s ON s.id = e.scope_unit_id
                       WHERE e.organisation_id = b.organisation_id
                         AND e.category_id = b.category_id AND e.currency = b.currency
                         AND e.expense_date BETWEEN b.period_start AND b.period_end
                         AND e.status IN ('APPROVED', 'PAID')
                   ), 0.00::numeric) AS actual_amount
            FROM expenditure_budgets b
            JOIN visible_units v ON v.id = b.unit_id
            JOIN organisational_units u ON u.id = b.unit_id
            JOIN expenditure_categories c ON c.id = b.category_id AND c.organisation_id = b.organisation_id
            WHERE b.organisation_id = ?
            ORDER BY b.period_start DESC, u.name, c.name, b.currency
            """, (rs, n) -> budgetDto(rs), scopeUnitId, organisationId, organisationId, organisationId);
    }

    @PostMapping("/organisations/{organisationId}/expenditure-budgets")
    @Transactional
    public ResponseEntity<BudgetDto> create(@PathVariable UUID organisationId, @Valid @RequestBody CreateBudget request) {
        requireBudgetAuthority();
        if (request.periodEnd().isBefore(request.periodStart())) {
            throw new IllegalArgumentException("Budget period end cannot be before its start date.");
        }
        UnitRow unit = requireUnit(request.unitId());
        CategoryRow category = requireCategory(request.categoryId());
        if (!unit.organisationId().equals(organisationId) || !category.organisationId().equals(organisationId)) {
            throw new IllegalArgumentException("The budget unit and expenditure category must belong to the selected organisation.");
        }
        if (!unit.active()) throw new ConflictException("A budget cannot be assigned to an inactive geographical unit.");
        if (!category.active()) throw new ConflictException("A budget cannot be assigned to an inactive expenditure category.");
        jdbc.queryForObject("SELECT id FROM expenditure_categories WHERE id = ? FOR UPDATE", UUID.class, request.categoryId());
        ensureNoOverlappingBudget(organisationId, request.unitId(), request.categoryId(),
            request.periodStart(), request.periodEnd(), request.currency().toUpperCase(), null);
        UUID id = UUID.randomUUID();
        UUID actor = actorId();
        jdbc.update("""
            INSERT INTO expenditure_budgets
                (id, organisation_id, unit_id, category_id, period_start, period_end,
                 budget_amount, currency, note, created_by, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, organisationId, request.unitId(), request.categoryId(), request.periodStart(),
            request.periodEnd(), request.budgetAmount(), request.currency().toUpperCase(),
            blankToNull(request.note()), actor, actor);
        audit.record("EXPENDITURE_BUDGET_CREATED", "EXPENDITURE_BUDGET", id,
            Map.of("organisationId", organisationId.toString(), "unitId", request.unitId().toString(),
                "categoryId", request.categoryId().toString(), "periodStart", request.periodStart().toString(),
                "periodEnd", request.periodEnd().toString(), "budgetAmount", request.budgetAmount().toPlainString(),
                "currency", request.currency().toUpperCase()));
        return ResponseEntity.status(HttpStatus.CREATED).body(requireBudget(id));
    }

    @PostMapping("/expenditure-budgets/{budgetId}/revisions")
    @Transactional
    public BudgetDto revise(@PathVariable UUID budgetId, @Valid @RequestBody ReviseBudget request) {
        requireBudgetAuthority();
        BudgetRow current = requireBudgetRow(budgetId, true);
        if (current.version() != request.expectedVersion()) {
            throw new ConflictException("This budget has changed. Reload it before submitting another revision.");
        }
        if (current.amount().compareTo(request.revisedAmount()) == 0) {
            throw new IllegalArgumentException("The revised amount must differ from the current budget.");
        }
        UUID actor = actorId();
        int nextVersion = current.version() + 1;
        jdbc.update("""
            INSERT INTO expenditure_budget_revisions
                (id, budget_id, previous_amount, revised_amount, previous_version, new_version, reason, revised_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), budgetId, current.amount(), request.revisedAmount(),
            current.version(), nextVersion, request.reason().trim(), actor);
        jdbc.update("""
            UPDATE expenditure_budgets
            SET budget_amount = ?, version = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND version = ?
            """, request.revisedAmount(), nextVersion, actor, budgetId, current.version());
        audit.record("EXPENDITURE_BUDGET_REVISED", "EXPENDITURE_BUDGET", budgetId,
            Map.of("previousAmount", current.amount().toPlainString(),
                "revisedAmount", request.revisedAmount().toPlainString(),
                "previousVersion", current.version(), "newVersion", nextVersion, "reason", request.reason().trim()));
        return requireBudget(budgetId);
    }

    @GetMapping("/expenditure-budgets/{budgetId}/revisions")
    public List<BudgetRevisionDto> revisions(@PathVariable UUID budgetId) {
        BudgetRow budget = requireBudgetRow(budgetId, false);
        authorization.requireUnitPermission("FINANCE_VIEW", budget.unitId());
        return jdbc.query("""
            SELECT r.id, r.previous_amount, r.revised_amount, r.previous_version, r.new_version,
                   r.reason, au.username AS revised_by, r.revised_at
            FROM expenditure_budget_revisions r
            JOIN admin_users au ON au.id = r.revised_by
            WHERE r.budget_id = ?
            ORDER BY r.new_version DESC
            """, (rs, n) -> new BudgetRevisionDto(rs.getObject("id", UUID.class).toString(),
                rs.getBigDecimal("previous_amount"), rs.getBigDecimal("revised_amount"),
                rs.getInt("previous_version"), rs.getInt("new_version"), rs.getString("reason"),
                rs.getString("revised_by"), rs.getTimestamp("revised_at").toInstant().toString()), budgetId);
    }

    private void requireBudgetAuthority() {
        authorization.requireCentralAdministrator();
        authorization.requireGlobalPermission("FINANCE_BUDGET_MANAGE");
    }

    private void ensureNoOverlappingBudget(UUID organisationId, UUID unitId, UUID categoryId,
            LocalDate from, LocalDate to, String currency, UUID excludedBudgetId) {
        Set<UUID> relatedUnits = new HashSet<>(jdbc.query("""
            WITH RECURSIVE descendants(id) AS (
                SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
                UNION ALL
                SELECT child.id FROM organisational_units child
                JOIN descendants parent ON child.parent_id = parent.id
                WHERE child.organisation_id = ?
            ), ancestors(id, parent_id) AS (
                SELECT id, parent_id FROM organisational_units WHERE id = ? AND organisation_id = ?
                UNION ALL
                SELECT parent.id, parent.parent_id FROM organisational_units parent
                JOIN ancestors child ON child.parent_id = parent.id
                WHERE parent.organisation_id = ?
            )
            SELECT id FROM descendants UNION SELECT id FROM ancestors
            """, (rs, n) -> rs.getObject(1, UUID.class),
            unitId, organisationId, organisationId, unitId, organisationId, organisationId));
        List<UUID> conflicts = jdbc.query("""
            SELECT unit_id FROM expenditure_budgets
            WHERE organisation_id = ? AND category_id = ? AND currency = ?
              AND period_start <= ? AND period_end >= ?
              AND id <> COALESCE(?::uuid, '00000000-0000-0000-0000-000000000000'::uuid)
            """, (rs, n) -> rs.getObject(1, UUID.class), organisationId, categoryId, currency,
            to, from, excludedBudgetId);
        if (conflicts.stream().anyMatch(relatedUnits::contains)) {
            throw new ConflictException("This category already has an overlapping budget for the same unit or an ancestor/descendant unit.");
        }
    }

    private BudgetDto requireBudget(UUID id) {
        return jdbc.query("""
            SELECT b.id, b.organisation_id, b.unit_id, u.level AS unit_level, u.code AS unit_code,
                   u.name AS unit_name, b.category_id, c.code AS category_code, c.name AS category_name,
                   b.period_start, b.period_end, b.budget_amount, b.currency, b.note, b.version,
                   b.created_at, b.updated_at,
                   COALESCE((
                       WITH RECURSIVE budget_scope(id) AS (
                           SELECT b.unit_id
                           UNION ALL
                           SELECT child.id FROM organisational_units child
                           JOIN budget_scope parent ON child.parent_id = parent.id
                           WHERE child.organisation_id = b.organisation_id
                       )
                       SELECT SUM(e.amount) FROM expenditures e
                       JOIN budget_scope s ON s.id = e.scope_unit_id
                       WHERE e.organisation_id = b.organisation_id
                         AND e.category_id = b.category_id AND e.currency = b.currency
                         AND e.expense_date BETWEEN b.period_start AND b.period_end
                         AND e.status IN ('APPROVED', 'PAID')
                   ), 0.00::numeric) AS actual_amount
            FROM expenditure_budgets b
            JOIN organisational_units u ON u.id = b.unit_id
            JOIN expenditure_categories c ON c.id = b.category_id AND c.organisation_id = b.organisation_id
            WHERE b.id = ?
            """, (rs, n) -> budgetDto(rs), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Expenditure budget not found."));
    }

    private BudgetRow requireBudgetRow(UUID id, boolean lock) {
        return jdbc.query("SELECT id, organisation_id, unit_id, category_id, period_start, period_end, budget_amount, currency, version FROM expenditure_budgets WHERE id = ?"
            + (lock ? " FOR UPDATE" : ""), (rs, n) -> new BudgetRow(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getObject("unit_id", UUID.class),
                rs.getObject("category_id", UUID.class), rs.getDate("period_start").toLocalDate(),
                rs.getDate("period_end").toLocalDate(), rs.getBigDecimal("budget_amount"),
                rs.getString("currency"), rs.getInt("version")), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Expenditure budget not found."));
    }

    private UnitRow requireUnit(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, active FROM organisational_units WHERE id = ?
            """, (rs, n) -> new UnitRow(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getBoolean("active")), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Geographical unit not found."));
    }

    private CategoryRow requireCategory(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, active FROM expenditure_categories WHERE id = ?
            """, (rs, n) -> new CategoryRow(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getBoolean("active")), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Expenditure category not found."));
    }

    private UUID actorId() {
        return jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
    }

    private static BudgetDto budgetDto(java.sql.ResultSet rs) throws java.sql.SQLException {
        BigDecimal budget = rs.getBigDecimal("budget_amount");
        BigDecimal actual = rs.getBigDecimal("actual_amount");
        BigDecimal remaining = budget.subtract(actual);
        BigDecimal utilisation = actual.multiply(new BigDecimal("100"))
            .divide(budget, 2, RoundingMode.HALF_UP);
        return new BudgetDto(rs.getObject("id", UUID.class).toString(),
            rs.getObject("unit_id", UUID.class).toString(), rs.getString("unit_level"),
            rs.getString("unit_code"), rs.getString("unit_name"),
            rs.getObject("category_id", UUID.class).toString(), rs.getString("category_code"),
            rs.getString("category_name"), rs.getDate("period_start").toLocalDate().toString(),
            rs.getDate("period_end").toLocalDate().toString(), budget, actual, remaining,
            utilisation, rs.getString("currency"), rs.getString("note"), rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant().toString(),
            rs.getTimestamp("updated_at").toInstant().toString());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreateBudget(@NotNull UUID unitId, @NotNull UUID categoryId,
        @NotNull LocalDate periodStart, @NotNull LocalDate periodEnd,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal budgetAmount,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency, @Size(max = 1000) String note) {}
    public record ReviseBudget(@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal revisedAmount,
        @NotNull Integer expectedVersion, @NotBlank @Size(max = 1000) String reason) {}
    public record BudgetDto(String id, String unitId, String unitLevel, String unitCode, String unitName,
        String categoryId, String categoryCode, String categoryName, String periodStart, String periodEnd,
        BigDecimal budgetAmount, BigDecimal actualAmount, BigDecimal remainingAmount,
        BigDecimal utilisationPercentage, String currency, String note, int version,
        String createdAt, String updatedAt) {}
    public record BudgetRevisionDto(String id, BigDecimal previousAmount, BigDecimal revisedAmount,
        int previousVersion, int newVersion, String reason, String revisedBy, String revisedAt) {}
    private record UnitRow(UUID id, UUID organisationId, boolean active) {}
    private record CategoryRow(UUID id, UUID organisationId, boolean active) {}
    private record BudgetRow(UUID id, UUID organisationId, UUID unitId, UUID categoryId,
        LocalDate periodStart, LocalDate periodEnd, BigDecimal amount, String currency, int version) {}
}
