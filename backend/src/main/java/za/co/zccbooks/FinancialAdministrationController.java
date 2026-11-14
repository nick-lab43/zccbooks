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
public class FinancialAdministrationController {
    private static final List<String> SCOPE_LEVELS = List.of("NYIKA", "TABHERA", "CELL");
    private static final List<String> CONTRIBUTION_TYPES = List.of("MANDATORY", "VOLUNTARY", "PROJECT");

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public FinancialAdministrationController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/organisations/{organisationId}/financial-management-access")
    public boolean managementAccess(@PathVariable UUID organisationId) {
        requireOrganisation(organisationId);
        authorization.requireOrganisationOrUnitPermission("FINANCE_VIEW", organisationId);
        return authorization.isCentralAdministrator();
    }

    @GetMapping("/organisations/{organisationId}/financial-streams")
    public List<FinancialStreamDto> streams(@PathVariable UUID organisationId) {
        requireOrganisation(organisationId);
        authorization.requireOrganisationOrUnitPermission("FINANCE_VIEW", organisationId);
        return jdbc.query("""
            SELECT id, code, name, active
            FROM financial_streams
            WHERE organisation_id = ?
            ORDER BY name
            """, (rs, n) -> new FinancialStreamDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("code"),
                rs.getString("name"), rs.getBoolean("active")), organisationId);
    }

    @PostMapping("/organisations/{organisationId}/financial-streams")
    @Transactional
    public ResponseEntity<FinancialStreamDto> createStream(@PathVariable UUID organisationId,
            @Valid @RequestBody CreateStream request) {
        requireOrganisation(organisationId);
        requireFinancialConfigurationAuthority();
        String code = request.code().trim().toUpperCase();
        if (exists("SELECT COUNT(*) FROM financial_streams WHERE organisation_id = ? AND LOWER(code) = LOWER(?)",
                organisationId, code)) {
            throw new ConflictException("A financial stream with this code already exists.");
        }
        UUID id = UUID.randomUUID();
        UUID actor = currentActorId();
        FinancialStreamDto created = jdbc.queryForObject("""
            INSERT INTO financial_streams (id, organisation_id, code, name, created_by)
            VALUES (?, ?, ?, ?, ?)
            RETURNING id, code, name, active
            """, (rs, n) -> new FinancialStreamDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("code"),
                rs.getString("name"), rs.getBoolean("active")),
            id, organisationId, code, request.name().trim(), actor);
        audit.record("FINANCIAL_STREAM_CREATED", "FINANCIAL_STREAM", id,
            Map.of("organisationId", organisationId.toString(), "code", code, "name", request.name().trim()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/financial-streams/{streamId}")
    @Transactional
    public FinancialStreamDto updateStream(@PathVariable UUID streamId, @Valid @RequestBody UpdateStream request) {
        requireFinancialConfigurationAuthority();
        StreamRow stream = requireStream(streamId);
        if (!request.active()) {
            Integer activeCategories = jdbc.queryForObject("""
                SELECT COUNT(*) FROM contribution_categories
                WHERE stream_id = ? AND active = TRUE
                """, Integer.class, streamId);
            if (activeCategories != null && activeCategories > 0) {
                throw new ConflictException("Deactivate or move active contribution categories before deactivating this stream.");
            }
        }
        jdbc.update("""
            UPDATE financial_streams
            SET name = ?, active = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, request.name().trim(), request.active(), streamId);
        audit.record("FINANCIAL_STREAM_UPDATED", "FINANCIAL_STREAM", streamId,
            Map.of("previousActive", stream.active(), "active", request.active(), "name", request.name().trim()));
        return requireStream(streamId).toDto();
    }

    @GetMapping("/organisations/{organisationId}/contribution-categories")
    public List<ContributionCategoryDto> categories(@PathVariable UUID organisationId) {
        requireOrganisation(organisationId);
        authorization.requireOrganisationOrUnitPermission("FINANCE_VIEW", organisationId);
        return jdbc.query("""
            SELECT c.id, c.stream_id, s.code AS stream_code, s.name AS stream_name,
                   c.code, c.name, c.description, c.contribution_type, c.active,
                   COALESCE(STRING_AGG(sl.scope_level, ',' ORDER BY sl.scope_level), '') AS scope_levels
            FROM contribution_categories c
            JOIN financial_streams s ON s.id = c.stream_id AND s.organisation_id = c.organisation_id
            LEFT JOIN contribution_category_scope_levels sl ON sl.category_id = c.id
            WHERE c.organisation_id = ?
            GROUP BY c.id, c.stream_id, s.code, s.name
            ORDER BY c.name
            """, (rs, n) -> new ContributionCategoryDto(
                rs.getObject("id", UUID.class).toString(), rs.getObject("stream_id", UUID.class).toString(),
                rs.getString("stream_code"), rs.getString("stream_name"), rs.getString("code"),
                rs.getString("name"), rs.getString("description"), rs.getString("contribution_type"),
                rs.getBoolean("active"), rs.getString("scope_levels").isBlank()
                    ? List.of() : List.of(rs.getString("scope_levels").split(","))), organisationId);
    }

    @PostMapping("/organisations/{organisationId}/contribution-categories")
    @Transactional
    public ResponseEntity<ContributionCategoryDto> createCategory(@PathVariable UUID organisationId,
            @Valid @RequestBody CreateCategory request) {
        requireOrganisation(organisationId);
        requireFinancialConfigurationAuthority();
        validateScopeLevels(request.scopeLevels());
        if (!CONTRIBUTION_TYPES.contains(request.contributionType())) {
            throw new IllegalArgumentException("Unsupported contribution type.");
        }
        requireStreamInOrganisation(request.streamId(), organisationId, true);
        String code = request.code().trim().toUpperCase();
        if (exists("SELECT COUNT(*) FROM contribution_categories WHERE organisation_id = ? AND LOWER(code) = LOWER(?)",
                organisationId, code)) {
            throw new ConflictException("A contribution category with this code already exists in this organisation.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO contribution_categories
                (id, organisation_id, stream_id, code, name, description, contribution_type, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, id, organisationId, request.streamId(), code, request.name().trim(),
            blankToNull(request.description()), request.contributionType(), currentActorId());
        for (String level : request.scopeLevels().stream().distinct().toList()) {
            jdbc.update("""
                INSERT INTO contribution_category_scope_levels (category_id, scope_level)
                VALUES (?, ?)
                """, id, level);
        }
        audit.record("CONTRIBUTION_CATEGORY_CREATED", "CONTRIBUTION_CATEGORY", id,
            Map.of("organisationId", organisationId.toString(), "streamId", request.streamId().toString(),
                "code", code, "name", request.name().trim(), "type", request.contributionType(),
                "scopeLevels", request.scopeLevels().stream().distinct().toList()));
        return ResponseEntity.status(HttpStatus.CREATED).body(requireCategory(id));
    }

    @PatchMapping("/contribution-categories/{categoryId}")
    @Transactional
    public ContributionCategoryDto updateCategory(@PathVariable UUID categoryId, @Valid @RequestBody UpdateCategory request) {
        requireFinancialConfigurationAuthority();
        ContributionCategoryDto existing = requireCategory(categoryId);
        validateScopeLevels(request.scopeLevels());
        if (!CONTRIBUTION_TYPES.contains(request.contributionType())) {
            throw new IllegalArgumentException("Unsupported contribution type.");
        }
        if (request.active() && !exists("""
            SELECT COUNT(*)
            FROM financial_streams s
            JOIN contribution_categories c ON c.stream_id = s.id AND c.organisation_id = s.organisation_id
            WHERE c.id = ? AND s.active = TRUE
            """, categoryId)) {
            throw new ConflictException("Reactivate the category's financial stream before reactivating this category.");
        }
        List<String> targetLevels = jdbc.query("""
            SELECT DISTINCT u.level
            FROM financial_targets t
            JOIN organisational_units u ON u.id = t.unit_id AND u.organisation_id = t.organisation_id
            WHERE t.category_id = ?
            """, (rs, n) -> rs.getString(1), categoryId);
        if (targetLevels.stream().anyMatch(level -> !request.scopeLevels().contains(level))) {
            throw new ConflictException("The category scope cannot exclude a geographical level that already has financial targets.");
        }
        jdbc.update("""
            UPDATE contribution_categories
            SET name = ?, description = ?, contribution_type = ?, active = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, request.name().trim(), blankToNull(request.description()),
            request.contributionType(), request.active(), categoryId);
        jdbc.update("DELETE FROM contribution_category_scope_levels WHERE category_id = ?", categoryId);
        for (String level : request.scopeLevels().stream().distinct().toList()) {
            jdbc.update("INSERT INTO contribution_category_scope_levels (category_id, scope_level) VALUES (?, ?)",
                categoryId, level);
        }
        audit.record("CONTRIBUTION_CATEGORY_UPDATED", "CONTRIBUTION_CATEGORY", categoryId,
            Map.of("previousActive", existing.active(), "active", request.active(),
                "name", request.name().trim(), "type", request.contributionType(),
                "scopeLevels", request.scopeLevels().stream().distinct().toList()));
        return requireCategory(categoryId);
    }

    @GetMapping("/organisations/{organisationId}/financial-targets")
    public List<FinancialTargetDto> targets(@PathVariable UUID organisationId) {
        requireOrganisation(organisationId);
        authorization.requireOrganisationOrUnitPermission("FINANCE_VIEW", organisationId);
        String sql = """
            SELECT t.id, t.category_id, c.code AS category_code, c.name AS category_name,
                   s.name AS stream_name, t.unit_id, u.level AS unit_level, u.code AS unit_code,
                   u.name AS unit_name, t.period_start, t.period_end, t.target_amount,
                   t.currency, t.note
            FROM financial_targets t
            JOIN contribution_categories c ON c.id = t.category_id AND c.organisation_id = t.organisation_id
            JOIN financial_streams s ON s.id = c.stream_id AND s.organisation_id = c.organisation_id
            JOIN organisational_units u ON u.id = t.unit_id AND u.organisation_id = t.organisation_id
            WHERE t.organisation_id = ?
            """;
        if (!authorization.hasOrganisationWidePermission("FINANCE_VIEW", organisationId)) {
            sql += """
                AND EXISTS (
                    WITH RECURSIVE visible_units AS (
                        SELECT a.scope_unit_id AS id
                        FROM admin_role_assignments a
                        JOIN admin_users au ON au.id = a.user_id
                        JOIN admin_roles ar ON ar.id = a.role_id
                        JOIN admin_role_permissions rp ON rp.role_id = ar.id
                        WHERE a.scope_type = 'UNIT' AND a.organisation_id = ?
                          AND a.revoked_at IS NULL AND LOWER(au.username) = LOWER(?)
                          AND rp.permission_code = 'FINANCE_VIEW'
                        UNION
                        SELECT child.id
                        FROM organisational_units child
                        JOIN visible_units parent ON child.parent_id = parent.id
                        WHERE child.organisation_id = ?
                    )
                    SELECT 1 FROM visible_units v WHERE v.id = t.unit_id
                )
                """;
            return jdbc.query(sql + " ORDER BY t.period_start DESC, u.name, c.name",
                (rs, n) -> targetDto(rs), organisationId, organisationId,
                authorization.currentUsername(), organisationId);
        }
        return jdbc.query(sql + " ORDER BY t.period_start DESC, u.name, c.name",
            (rs, n) -> targetDto(rs), organisationId);
    }

    @PostMapping("/organisations/{organisationId}/financial-targets")
    @Transactional
    public ResponseEntity<FinancialTargetDto> createTarget(@PathVariable UUID organisationId,
            @Valid @RequestBody CreateTarget request) {
        requireOrganisation(organisationId);
        requireFinancialConfigurationAuthority();
        CategoryRow category = requireCategoryRow(request.categoryId());
        UnitRow unit = requireUnit(request.unitId());
        if (!category.organisationId().equals(organisationId) || !unit.organisationId().equals(organisationId)) {
            throw new IllegalArgumentException("The category and target unit must belong to the selected organisation.");
        }
        if (!unit.active()) throw new ConflictException("Financial targets cannot be assigned to an inactive geographical unit.");
        if (!category.active()) throw new ConflictException("Financial targets cannot be assigned to an inactive category.");
        if (!category.scopeLevels().contains(unit.level())) {
            throw new IllegalArgumentException("This category is not configured for the selected geographical level.");
        }
        if (request.periodEnd().isBefore(request.periodStart())) {
            throw new IllegalArgumentException("Target period end cannot be before its start date.");
        }
        jdbc.queryForObject("SELECT id FROM contribution_categories WHERE id = ? FOR UPDATE",
            UUID.class, request.categoryId());
        Integer overlaps = jdbc.queryForObject("""
            SELECT COUNT(*) FROM financial_targets
            WHERE category_id = ? AND unit_id = ?
              AND period_start <= ? AND period_end >= ?
            """, Integer.class, request.categoryId(), request.unitId(),
            request.periodEnd(), request.periodStart());
        if (overlaps != null && overlaps > 0) {
            throw new ConflictException("A target for this category and geographical unit already overlaps the selected period.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO financial_targets
                (id, organisation_id, category_id, unit_id, period_start, period_end,
                 target_amount, currency, note, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, organisationId, request.categoryId(), request.unitId(),
            request.periodStart(), request.periodEnd(), request.targetAmount(),
            request.currency().toUpperCase(), blankToNull(request.note()), currentActorId());
        audit.record("FINANCIAL_TARGET_CREATED", "FINANCIAL_TARGET", id,
            Map.of("organisationId", organisationId.toString(), "categoryId", request.categoryId().toString(),
                "unitId", request.unitId().toString(), "periodStart", request.periodStart().toString(),
                "periodEnd", request.periodEnd().toString(), "targetAmount", request.targetAmount().toPlainString(),
                "currency", request.currency().toUpperCase()));
        return ResponseEntity.status(HttpStatus.CREATED).body(requireTarget(id));
    }

    private ContributionCategoryDto requireCategory(UUID id) {
        return jdbc.query("""
            SELECT c.id, c.stream_id, s.code AS stream_code, s.name AS stream_name,
                   c.code, c.name, c.description, c.contribution_type, c.active,
                   COALESCE(STRING_AGG(sl.scope_level, ',' ORDER BY sl.scope_level), '') AS scope_levels
            FROM contribution_categories c
            JOIN financial_streams s ON s.id = c.stream_id AND s.organisation_id = c.organisation_id
            LEFT JOIN contribution_category_scope_levels sl ON sl.category_id = c.id
            WHERE c.id = ?
            GROUP BY c.id, c.stream_id, s.code, s.name
            """, (rs, n) -> new ContributionCategoryDto(
                rs.getObject("id", UUID.class).toString(), rs.getObject("stream_id", UUID.class).toString(),
                rs.getString("stream_code"), rs.getString("stream_name"), rs.getString("code"),
                rs.getString("name"), rs.getString("description"), rs.getString("contribution_type"),
                rs.getBoolean("active"), rs.getString("scope_levels").isBlank()
                    ? List.of() : List.of(rs.getString("scope_levels").split(","))), id)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Contribution category not found."));
    }

    private CategoryRow requireCategoryRow(UUID id) {
        return jdbc.query("""
            SELECT c.id, c.organisation_id, c.active,
                   COALESCE(ARRAY_AGG(sl.scope_level) FILTER (WHERE sl.scope_level IS NOT NULL), ARRAY[]::VARCHAR[]) AS scope_levels
            FROM contribution_categories c
            LEFT JOIN contribution_category_scope_levels sl ON sl.category_id = c.id
            WHERE c.id = ?
            GROUP BY c.id
            """, (rs, n) -> {
                java.sql.Array array = rs.getArray("scope_levels");
                String[] levels = (String[]) array.getArray();
                return new CategoryRow(rs.getObject("id", UUID.class),
                    rs.getObject("organisation_id", UUID.class), rs.getBoolean("active"), List.of(levels));
            }, id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Contribution category not found."));
    }

    private FinancialTargetDto requireTarget(UUID id) {
        return jdbc.query("""
            SELECT t.id, t.category_id, c.code AS category_code, c.name AS category_name,
                   s.name AS stream_name, t.unit_id, u.level AS unit_level, u.code AS unit_code,
                   u.name AS unit_name, t.period_start, t.period_end, t.target_amount,
                   t.currency, t.note
            FROM financial_targets t
            JOIN contribution_categories c ON c.id = t.category_id AND c.organisation_id = t.organisation_id
            JOIN financial_streams s ON s.id = c.stream_id AND s.organisation_id = c.organisation_id
            JOIN organisational_units u ON u.id = t.unit_id AND u.organisation_id = t.organisation_id
            WHERE t.id = ?
            """, (rs, n) -> targetDto(rs), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Financial target not found."));
    }

    private FinancialTargetDto targetDto(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FinancialTargetDto(rs.getObject("id", UUID.class).toString(),
            rs.getObject("category_id", UUID.class).toString(), rs.getString("category_code"),
            rs.getString("category_name"), rs.getString("stream_name"),
            rs.getObject("unit_id", UUID.class).toString(), rs.getString("unit_level"),
            rs.getString("unit_code"), rs.getString("unit_name"),
            rs.getDate("period_start").toLocalDate().toString(),
            rs.getDate("period_end").toLocalDate().toString(),
            rs.getBigDecimal("target_amount"), rs.getString("currency"), rs.getString("note"));
    }

    private void requireFinancialConfigurationAuthority() {
        authorization.requireCentralAdministrator();
        authorization.requireGlobalPermission("FINANCE_CONFIGURE");
    }

    private void requireOrganisation(UUID id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM organisations WHERE id = ?", Integer.class, id);
        if (count == null || count == 0) throw new ResourceNotFoundException("Organisation not found.");
    }

    private StreamRow requireStream(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, code, name, active FROM financial_streams WHERE id = ?
            """, (rs, n) -> new StreamRow(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getBoolean("active")), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Financial stream not found."));
    }

    private void requireStreamInOrganisation(UUID id, UUID organisationId, boolean active) {
        String sql = """
            SELECT COUNT(*) FROM financial_streams
            WHERE id = ? AND organisation_id = ?
            """ + (active ? " AND active = TRUE" : "");
        Integer count = jdbc.queryForObject(sql, Integer.class, id, organisationId);
        if (count == null || count == 0) throw new ResourceNotFoundException("Active financial stream not found in this organisation.");
    }

    private UnitRow requireUnit(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, level, active FROM organisational_units WHERE id = ?
            """, (rs, n) -> new UnitRow(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getString("level"), rs.getBoolean("active")),
            id).stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Geographical unit not found."));
    }

    private UUID currentActorId() {
        return jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private static void validateScopeLevels(List<String> levels) {
        if (levels == null || levels.isEmpty() || levels.stream().anyMatch(level -> !SCOPE_LEVELS.contains(level))) {
            throw new IllegalArgumentException("Select at least one valid scope level: Nyika, Tabhera or Cell.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreateStream(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,40}") String code,
        @NotBlank @Size(max = 100) String name) {}
    public record UpdateStream(@NotBlank @Size(max = 100) String name, boolean active) {}
    public record FinancialStreamDto(String id, String code, String name, boolean active) {}
    public record CreateCategory(@NotNull UUID streamId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,60}") String code,
        @NotBlank @Size(max = 120) String name, @Size(max = 500) String description,
        @NotBlank String contributionType, @NotEmpty List<String> scopeLevels) {}
    public record UpdateCategory(@NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description, @NotBlank String contributionType,
        boolean active, @NotEmpty List<String> scopeLevels) {}
    public record ContributionCategoryDto(String id, String streamId, String streamCode, String streamName,
        String code, String name, String description, String contributionType, boolean active,
        List<String> scopeLevels) {}
    public record CreateTarget(@NotNull UUID categoryId, @NotNull UUID unitId,
        @NotNull java.time.LocalDate periodStart, @NotNull java.time.LocalDate periodEnd,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal targetAmount,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency, @Size(max = 1000) String note) {}
    public record FinancialTargetDto(String id, String categoryId, String categoryCode,
        String categoryName, String streamName, String unitId, String unitLevel, String unitCode,
        String unitName, String periodStart, String periodEnd, BigDecimal targetAmount,
        String currency, String note) {}
    private record StreamRow(UUID id, UUID organisationId, String code, String name, boolean active) {
        FinancialStreamDto toDto() { return new FinancialStreamDto(id.toString(), code, name, active); }
    }
    private record CategoryRow(UUID id, UUID organisationId, boolean active, List<String> scopeLevels) {}
    private record UnitRow(UUID id, UUID organisationId, String level, boolean active) {}
}
