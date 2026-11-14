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
public class ContributionController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public ContributionController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @PostMapping("/organisations/{organisationId}/contributions")
    @Transactional
    public ResponseEntity<ContributionDto> capture(@PathVariable UUID organisationId,
            @Valid @RequestBody CaptureContribution request) {
        authorization.requireUnitPermission("FINANCE_CAPTURE", request.scopeUnitId());

        ContributionContext context = jdbc.query("""
            SELECT m.id AS member_id, m.organisation_id, m.cell_id, m.active AS member_active,
                   c.id AS category_id, c.stream_id, c.active AS category_active,
                   s.active AS stream_active, u.level AS scope_level, u.active AS scope_active,
                   EXISTS (
                     WITH RECURSIVE ancestors AS (
                       SELECT id, parent_id, organisation_id FROM organisational_units WHERE id = m.cell_id
                       UNION ALL
                       SELECT p.id, p.parent_id, p.organisation_id
                       FROM organisational_units p JOIN ancestors a
                         ON p.id = a.parent_id AND p.organisation_id = a.organisation_id
                     )
                     SELECT 1 FROM ancestors WHERE id = ? AND organisation_id = m.organisation_id
                   ) AS scope_contains_member,
                   EXISTS (
                     SELECT 1 FROM contribution_category_scope_levels sl
                     WHERE sl.category_id = c.id AND sl.scope_level = u.level
                   ) AS category_allows_scope
            FROM members m
            JOIN contribution_categories c ON c.id = ?
            JOIN financial_streams s ON s.id = c.stream_id AND s.organisation_id = c.organisation_id
            JOIN organisational_units u ON u.id = ?
            WHERE m.id = ? AND m.organisation_id = ?
              AND c.organisation_id = ? AND s.organisation_id = ? AND u.organisation_id = ?
            """, (rs, n) -> new ContributionContext(
                rs.getObject("member_id", UUID.class), rs.getObject("organisation_id", UUID.class),
                rs.getObject("cell_id", UUID.class), rs.getBoolean("member_active"),
                rs.getObject("category_id", UUID.class), rs.getObject("stream_id", UUID.class),
                rs.getBoolean("category_active"), rs.getBoolean("stream_active"),
                rs.getString("scope_level"), rs.getBoolean("scope_active"),
                rs.getBoolean("scope_contains_member"), rs.getBoolean("category_allows_scope")),
            request.scopeUnitId(), request.categoryId(), request.scopeUnitId(),
            request.memberId(), organisationId, organisationId, organisationId, organisationId)
            .stream().findFirst().orElseThrow(() ->
                new ResourceNotFoundException("Member, category or geographical scope was not found in this organisation."));

        if (!context.memberActive()) throw new ConflictException("Contributions cannot be captured for an inactive member.");
        if (!context.categoryActive()) throw new ConflictException("The contribution category is inactive.");
        if (!context.streamActive()) throw new ConflictException("The category's financial stream is inactive.");
        if (!context.scopeActive()) throw new ConflictException("Contributions cannot be recorded against an inactive geographical unit.");
        if (!context.scopeContainsMember()) throw new IllegalArgumentException("The selected financial scope must contain the member's assigned Cell.");
        if (!context.categoryAllowsScope()) throw new IllegalArgumentException("The category is not configured for the selected geographical level.");

        jdbc.update("""
            INSERT INTO financial_receipt_sequences (organisation_id, next_number)
            VALUES (?, 1) ON CONFLICT (organisation_id) DO NOTHING
            """, organisationId);
        jdbc.queryForObject("""
            SELECT next_number FROM financial_receipt_sequences
            WHERE organisation_id = ? FOR UPDATE
            """, Long.class, organisationId);

        ContributionDto existing = jdbc.query("""
            SELECT c.id, c.receipt_number, c.member_id, m.first_name, m.last_name,
                   c.category_id, cat.name AS category_name, c.stream_id, fs.name AS stream_name,
                   c.scope_unit_id, u.name AS scope_name, c.amount, c.currency,
                   c.contribution_date, c.payment_method, c.payment_reference, c.note, c.captured_at
            FROM contributions c
            JOIN members m ON m.id = c.member_id
            JOIN contribution_categories cat ON cat.id = c.category_id
            JOIN financial_streams fs ON fs.id = c.stream_id
            JOIN organisational_units u ON u.id = c.scope_unit_id
            WHERE c.organisation_id = ? AND c.idempotency_key = ?
            """, (rs, n) -> mapContribution(rs), organisationId, request.idempotencyKey())
            .stream().findFirst().orElse(null);
        if (existing != null) return ResponseEntity.ok(existing);

        UUID actorId = jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
        Long sequence = jdbc.queryForObject("""
            UPDATE financial_receipt_sequences
            SET next_number = next_number + 1, updated_at = CURRENT_TIMESTAMP
            WHERE organisation_id = ?
            RETURNING next_number - 1
            """, Long.class, organisationId);
        if (sequence == null) throw new IllegalStateException("Receipt sequence could not be allocated.");

        String receiptNumber = jdbc.queryForObject(
            "SELECT 'ZCC-' || UPPER(SUBSTRING(REPLACE(?::text, '-', ''), 1, 6)) || '-' || EXTRACT(YEAR FROM ?::date)::int || '-' || LPAD(?::text, 7, '0')",
            String.class, organisationId, request.contributionDate(), sequence);
        UUID contributionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO contributions
                (id, organisation_id, member_id, category_id, stream_id, scope_unit_id,
                 receipt_number, receipt_sequence, amount, currency, contribution_date,
                 payment_method, payment_reference, note, idempotency_key, captured_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ZAR', ?, ?, ?, ?, ?, ?)
            """, contributionId, organisationId, request.memberId(), request.categoryId(),
            context.streamId(), request.scopeUnitId(), receiptNumber, sequence, request.amount(),
            request.contributionDate(), request.paymentMethod(), blankToNull(request.paymentReference()),
            blankToNull(request.note()), request.idempotencyKey(), actorId);
        jdbc.update("""
            INSERT INTO contribution_deposit_followups (id, contribution_id)
            VALUES (?, ?)
            """, UUID.randomUUID(), contributionId);
        audit.record("CONTRIBUTION_POSTED", "CONTRIBUTION", contributionId,
            Map.of("organisationId", organisationId.toString(), "memberId", request.memberId().toString(),
                "categoryId", request.categoryId().toString(), "scopeUnitId", request.scopeUnitId().toString(),
                "receiptNumber", receiptNumber, "amount", request.amount().toPlainString(),
                "currency", "ZAR", "contributionDate", request.contributionDate().toString()));
        return ResponseEntity.status(HttpStatus.CREATED).body(requireContribution(contributionId));
    }

    @GetMapping("/units/{cellId}/financial-members")
    public List<FinancialMemberDto> financialMembers(@PathVariable UUID cellId,
            @RequestParam UUID scopeUnitId) {
        authorization.requireUnitPermission("FINANCE_CAPTURE", scopeUnitId);
        Integer validScope = jdbc.queryForObject("""
            WITH RECURSIVE ancestors AS (
                SELECT id, parent_id, organisation_id, level
                FROM organisational_units WHERE id = ?
                UNION ALL
                SELECT p.id, p.parent_id, p.organisation_id, p.level
                FROM organisational_units p JOIN ancestors a
                  ON p.id = a.parent_id AND p.organisation_id = a.organisation_id
            )
            SELECT COUNT(*) FROM ancestors WHERE id = ? AND level IN ('NYIKA', 'TABHERA', 'CELL')
            """, Integer.class, cellId, scopeUnitId);
        if (validScope == null || validScope == 0) {
            throw new IllegalArgumentException("The selected Cell is outside the authorised financial scope.");
        }
        return jdbc.query("""
            SELECT id, zcc_app_number, first_name, last_name
            FROM members
            WHERE cell_id = ? AND active = TRUE
            ORDER BY last_name, first_name
            """, (rs, n) -> new FinancialMemberDto(rs.getObject("id", UUID.class).toString(),
                rs.getString("zcc_app_number"), rs.getString("first_name"), rs.getString("last_name")), cellId);
    }

    @GetMapping("/organisations/{organisationId}/contributions")
    public List<ContributionDto> list(@PathVariable UUID organisationId,
            @RequestParam UUID scopeUnitId, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        authorization.requireUnitPermission("FINANCE_VIEW", scopeUnitId);
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("The end date cannot be before the start date.");
        }
        return jdbc.query("""
            SELECT c.id, c.receipt_number, c.member_id, m.first_name, m.last_name,
                   c.category_id, cat.name AS category_name, c.stream_id, fs.name AS stream_name,
                   c.scope_unit_id, u.name AS scope_name, c.amount, c.currency,
                   c.contribution_date, c.payment_method, c.payment_reference, c.note, c.captured_at
            FROM contributions c
            JOIN members m ON m.id = c.member_id
            JOIN contribution_categories cat ON cat.id = c.category_id
            JOIN financial_streams fs ON fs.id = c.stream_id
            JOIN organisational_units u ON u.id = c.scope_unit_id
            WHERE c.organisation_id = ? AND c.scope_unit_id = ?
              AND (?::date IS NULL OR c.contribution_date >= ?)
              AND (?::date IS NULL OR c.contribution_date <= ?)
            ORDER BY c.contribution_date DESC, c.captured_at DESC
            """, (rs, n) -> mapContribution(rs), organisationId, scopeUnitId,
            from, from, to, to);
    }

    @GetMapping("/contributions/{contributionId}/receipt")
    public ContributionDto receipt(@PathVariable UUID contributionId) {
        ContributionDto row = requireContribution(contributionId);
        UUID scopeId = UUID.fromString(row.scopeUnitId());
        authorization.requireUnitPermission("FINANCE_VIEW", scopeId);
        return row;
    }

    @GetMapping("/organisations/{organisationId}/financial-target-progress")
    public List<TargetProgressDto> targetProgress(@PathVariable UUID organisationId) {
        String sql = """
            SELECT t.id AS target_id, t.category_id, c.name AS category_name,
                   t.unit_id, u.name AS unit_name, t.period_start, t.period_end,
                   t.target_amount, t.currency,
                   COALESCE(SUM(p.amount), 0) AS received_amount
            FROM financial_targets t
            JOIN contribution_categories c ON c.id = t.category_id AND c.organisation_id = t.organisation_id
            JOIN organisational_units u ON u.id = t.unit_id AND u.organisation_id = t.organisation_id
            LEFT JOIN contributions p
              ON p.organisation_id = t.organisation_id
             AND p.category_id = t.category_id
             AND p.scope_unit_id = t.unit_id
             AND p.contribution_date BETWEEN t.period_start AND t.period_end
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
        }
        sql += """
            GROUP BY t.id, t.category_id, c.name, t.unit_id, u.name,
                     t.period_start, t.period_end, t.target_amount, t.currency
            ORDER BY t.period_start DESC, u.name, c.name
            """;
        var mapper = (org.springframework.jdbc.core.RowMapper<TargetProgressDto>) (rs, n) -> {
            BigDecimal target = rs.getBigDecimal("target_amount");
            BigDecimal received = rs.getBigDecimal("received_amount");
            BigDecimal percentage = target.signum() == 0 ? BigDecimal.ZERO :
                received.multiply(BigDecimal.valueOf(100)).divide(target, 2, java.math.RoundingMode.HALF_UP);
            return new TargetProgressDto(rs.getObject("target_id", UUID.class).toString(),
                rs.getString("category_name"), rs.getObject("unit_id", UUID.class).toString(),
                rs.getString("unit_name"), rs.getDate("period_start").toLocalDate().toString(),
                rs.getDate("period_end").toLocalDate().toString(), target, received, percentage, rs.getString("currency"));
        };
        if (!authorization.hasOrganisationWidePermission("FINANCE_VIEW", organisationId)) {
            return jdbc.query(sql, mapper, organisationId, organisationId,
                authorization.currentUsername(), organisationId);
        }
        return jdbc.query(sql, mapper, organisationId);
    }

    @GetMapping("/organisations/{organisationId}/deposit-followups")
    public List<DepositFollowupDto> depositFollowups(@PathVariable UUID organisationId,
            @RequestParam UUID scopeUnitId) {
        authorization.requireUnitPermission("FINANCE_VIEW", scopeUnitId);
        return jdbc.query("""
            SELECT f.id, f.status, f.note, f.created_at, c.id AS contribution_id,
                   c.receipt_number, m.first_name, m.last_name, cat.name AS category_name,
                   c.amount, c.currency, c.contribution_date
            FROM contribution_deposit_followups f
            JOIN contributions c ON c.id = f.contribution_id
            JOIN members m ON m.id = c.member_id
            JOIN contribution_categories cat ON cat.id = c.category_id
            WHERE c.organisation_id = ? AND c.scope_unit_id = ?
              AND f.status <> 'RESOLVED'
            ORDER BY f.created_at
            """, (rs, n) -> new DepositFollowupDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("status"),
                rs.getString("note"), rs.getString("receipt_number"),
                rs.getString("first_name") + " " + rs.getString("last_name"),
                rs.getString("category_name"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getDate("contribution_date").toLocalDate().toString()),
            organisationId, scopeUnitId);
    }

    @PatchMapping("/deposit-followups/{followupId}")
    @Transactional
    public DepositFollowupDto updateDepositFollowup(@PathVariable UUID followupId,
            @Valid @RequestBody UpdateDepositFollowup request) {
        DepositFollowupContext context = jdbc.query("""
            SELECT f.id, c.scope_unit_id, c.organisation_id, f.status
            FROM contribution_deposit_followups f
            JOIN contributions c ON c.id = f.contribution_id
            WHERE f.id = ? FOR UPDATE
            """, (rs, n) -> new DepositFollowupContext(rs.getObject("id", UUID.class),
                rs.getObject("scope_unit_id", UUID.class), rs.getObject("organisation_id", UUID.class),
                rs.getString("status")), followupId).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Deposit follow-up not found."));
        authorization.requireUnitPermission("FINANCE_CAPTURE", context.scopeUnitId());
        jdbc.update("""
            UPDATE contribution_deposit_followups
            SET status = ?, note = ?, assigned_to = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, request.status(), blankToNull(request.note()),
            jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
                UUID.class, authorization.currentUsername()), followupId);
        audit.record("DEPOSIT_FOLLOWUP_UPDATED", "DEPOSIT_FOLLOWUP", followupId,
            Map.of("previousStatus", context.status(), "status", request.status(),
                "scopeUnitId", context.scopeUnitId().toString()));
        return jdbc.query("""
            SELECT f.id, f.status, f.note, f.created_at, c.id AS contribution_id,
                   c.receipt_number, m.first_name, m.last_name, cat.name AS category_name,
                   c.amount, c.currency, c.contribution_date
            FROM contribution_deposit_followups f
            JOIN contributions c ON c.id = f.contribution_id
            JOIN members m ON m.id = c.member_id
            JOIN contribution_categories cat ON cat.id = c.category_id
            WHERE f.id = ?
            """, (rs, n) -> new DepositFollowupDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("status"),
                rs.getString("note"), rs.getString("receipt_number"),
                rs.getString("first_name") + " " + rs.getString("last_name"),
                rs.getString("category_name"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getDate("contribution_date").toLocalDate().toString()),
            followupId).get(0);
    }

    private ContributionDto requireContribution(UUID id) {
        return jdbc.query("""
            SELECT c.id, c.receipt_number, c.member_id, m.first_name, m.last_name,
                   c.category_id, cat.name AS category_name, c.stream_id, fs.name AS stream_name,
                   c.scope_unit_id, u.name AS scope_name, c.amount, c.currency,
                   c.contribution_date, c.payment_method, c.payment_reference, c.note, c.captured_at
            FROM contributions c
            JOIN members m ON m.id = c.member_id
            JOIN contribution_categories cat ON cat.id = c.category_id
            JOIN financial_streams fs ON fs.id = c.stream_id
            JOIN organisational_units u ON u.id = c.scope_unit_id
            WHERE c.id = ?
            """, (rs, n) -> mapContribution(rs), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Contribution receipt not found."));
    }

    private ContributionDto mapContribution(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ContributionDto(rs.getObject("id", UUID.class).toString(),
            rs.getString("receipt_number"), rs.getObject("member_id", UUID.class).toString(),
            rs.getString("first_name") + " " + rs.getString("last_name"),
            rs.getObject("category_id", UUID.class).toString(), rs.getString("category_name"),
            rs.getObject("stream_id", UUID.class).toString(), rs.getString("stream_name"),
            rs.getObject("scope_unit_id", UUID.class).toString(), rs.getString("scope_name"),
            rs.getBigDecimal("amount"), rs.getString("currency"),
            rs.getDate("contribution_date").toLocalDate().toString(), rs.getString("payment_method"),
            rs.getString("payment_reference"), rs.getString("note"),
            rs.getTimestamp("captured_at").toInstant().toString());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record FinancialMemberDto(String id, String zccAppNumber, String firstName, String lastName) {}
    public record UpdateDepositFollowup(@NotBlank @Pattern(regexp = "ACKNOWLEDGED|RESOLVED") String status,
        @Size(max = 500) String note) {}
    public record DepositFollowupDto(String id, String status, String note, String receiptNumber,
        String memberName, String categoryName, BigDecimal amount, String currency, String contributionDate) {}
    private record DepositFollowupContext(UUID id, UUID scopeUnitId, UUID organisationId, String status) {}
    public record CaptureContribution(@NotNull UUID memberId, @NotNull UUID categoryId,
        @NotNull UUID scopeUnitId, @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotNull LocalDate contributionDate, @NotBlank String paymentMethod,
        @Size(max = 120) String paymentReference, @Size(max = 500) String note,
        @NotBlank @Size(max = 100) String idempotencyKey) {}
    public record ContributionDto(String id, String receiptNumber, String memberId, String memberName,
        String categoryId, String categoryName, String streamId, String streamName,
        String scopeUnitId, String scopeName, BigDecimal amount, String currency,
        String contributionDate, String paymentMethod, String paymentReference, String note, String capturedAt) {}
    public record TargetProgressDto(String targetId, String categoryName, String unitId, String unitName,
        String periodStart, String periodEnd, BigDecimal targetAmount, BigDecimal receivedAmount,
        BigDecimal percentageAchieved, String currency) {}
    private record ContributionContext(UUID memberId, UUID organisationId, UUID cellId, boolean memberActive,
        UUID categoryId, UUID streamId, boolean categoryActive, boolean streamActive,
        String scopeLevel, boolean scopeActive, boolean scopeContainsMember, boolean categoryAllowsScope) {}
}
