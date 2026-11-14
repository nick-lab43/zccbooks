package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CellMembershipController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public CellMembershipController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/units/{cellId}/members")
    public List<CellMemberDto> members(@PathVariable UUID cellId, @RequestParam(required = false) String search) {
        Cell cell = requireCell(cellId);
        authorization.requireUnitPermission("MEMBERS_VIEW", cellId);
        String term = search == null ? "" : search.trim();
        String pattern = "%" + term + "%";
        return jdbc.query("""
            SELECT m.id, m.organisation_id, m.cell_id, m.zcc_app_number, m.first_name, m.last_name,
                   m.classification_id, c.name AS classification_name, m.active
            FROM members m
            LEFT JOIN classifications c ON c.id = m.classification_id
            WHERE m.organisation_id = ? AND m.cell_id = ?
              AND (? = '' OR m.first_name ILIKE ? OR m.last_name ILIKE ?
                   OR COALESCE(m.zcc_app_number, '') ILIKE ?)
            ORDER BY m.last_name, m.first_name
            """, (rs, n) -> new CellMemberDto(
                rs.getObject("id", UUID.class).toString(),
                rs.getObject("organisation_id", UUID.class).toString(),
                rs.getObject("cell_id", UUID.class).toString(),
                rs.getString("zcc_app_number"), rs.getString("first_name"), rs.getString("last_name"),
                rs.getObject("classification_id") == null ? null : rs.getObject("classification_id", UUID.class).toString(),
                rs.getString("classification_name"), rs.getBoolean("active")
            ), cell.organisationId(), cell.id(), term, pattern, pattern, pattern);
    }

    @PostMapping("/units/{cellId}/members")
    public ResponseEntity<CellMemberDto> createMember(@PathVariable UUID cellId,
            @Valid @RequestBody CreateCellMember request) {
        Cell cell = requireCell(cellId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", cellId);
        UUID classificationId = request.classificationId();
        if (classificationId != null) {
            Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM classifications
                WHERE id = ? AND organisation_id = ? AND active = TRUE
                """, Integer.class, classificationId, cell.organisationId());
            if (count == null || count == 0) {
                throw new IllegalArgumentException("Select an active classification belonging to this organisation.");
            }
        }
        UUID id = UUID.randomUUID();
        CellMemberDto created = jdbc.queryForObject("""
            INSERT INTO members
                (id, branch_id, organisation_id, cell_id, cell_level, zcc_app_number,
                 first_name, last_name, classification_id)
            VALUES (?, NULL, ?, ?, 'CELL', ?, ?, ?, ?)
            RETURNING id, organisation_id, cell_id, zcc_app_number, first_name, last_name,
                      classification_id, NULL::VARCHAR AS classification_name, active
            """, (rs, n) -> new CellMemberDto(
                rs.getObject("id", UUID.class).toString(),
                rs.getObject("organisation_id", UUID.class).toString(),
                rs.getObject("cell_id", UUID.class).toString(),
                rs.getString("zcc_app_number"), rs.getString("first_name"), rs.getString("last_name"),
                rs.getObject("classification_id") == null ? null : rs.getObject("classification_id", UUID.class).toString(),
                rs.getString("classification_name"), rs.getBoolean("active")
            ), id, cell.organisationId(), cell.id(), blankToNull(request.zccAppNumber()),
                request.firstName().trim(), request.lastName().trim(), classificationId);
        audit.record("MEMBER_REGISTERED", "MEMBER", id,
            Map.of("organisationId", cell.organisationId(), "cellId", cell.id()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/households/{householdId}/head-candidates")
    public List<CellMemberDto> headCandidates(@PathVariable UUID householdId) {
        Household household = requireHousehold(householdId);
        authorization.requireUnitPermission("MEMBERS_VIEW", household.cellId());
        return jdbc.query("""
            SELECT m.id, m.organisation_id, m.cell_id, m.zcc_app_number, m.first_name, m.last_name,
                   m.classification_id, c.name AS classification_name, m.active
            FROM member_household_memberships x
            JOIN members m ON m.id = x.member_id AND m.organisation_id = x.organisation_id
            LEFT JOIN classifications c ON c.id = m.classification_id
            WHERE x.household_id = ? AND x.valid_to IS NULL AND m.active = TRUE
            ORDER BY m.last_name, m.first_name
            """, (rs, n) -> new CellMemberDto(
                rs.getObject("id", UUID.class).toString(),
                rs.getObject("organisation_id", UUID.class).toString(),
                rs.getObject("cell_id", UUID.class).toString(),
                rs.getString("zcc_app_number"), rs.getString("first_name"), rs.getString("last_name"),
                rs.getObject("classification_id") == null ? null : rs.getObject("classification_id", UUID.class).toString(),
                rs.getString("classification_name"), rs.getBoolean("active")
            ), household.id());
    }

    @PutMapping("/households/{householdId}/head")
    public ResponseEntity<Void> setHouseholdHead(@PathVariable UUID householdId,
            @Valid @RequestBody SetHouseholdHead request) {
        Household household = requireHousehold(householdId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", household.cellId());
        int updated = jdbc.update("""
            UPDATE households SET household_head_member_id = ?
            WHERE id = ? AND organisation_id = ?
            """, request.memberId(), household.id(), household.organisationId());
        if (updated == 0) throw new ResourceNotFoundException("Household not found.");
        audit.record("HOUSEHOLD_HEAD_CHANGED", "HOUSEHOLD", household.id(),
            Map.of("memberId", request.memberId() == null ? "" : request.memberId().toString()));
        return ResponseEntity.noContent().build();
    }

    private Cell requireCell(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, level FROM organisational_units WHERE id = ?
            """, (rs, n) -> new Cell(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getString("level")), id)
            .stream().findFirst().map(cell -> {
                if (!"CELL".equals(cell.level())) throw new IllegalArgumentException("Member registration must target a Cell.");
                return cell;
            }).orElseThrow(() -> new ResourceNotFoundException("Cell not found."));
    }

    private Household requireHousehold(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, cell_id FROM households WHERE id = ?
            """, (rs, n) -> new Household(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getObject("cell_id", UUID.class)), id)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Household not found."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreateCellMember(@Size(max = 80) String zccAppNumber,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName, UUID classificationId) {}
    public record SetHouseholdHead(UUID memberId) {}
    public record CellMemberDto(String id, String organisationId, String cellId, String zccAppNumber,
            String firstName, String lastName, String classificationId, String classificationName, boolean active) {}
    private record Cell(UUID id, UUID organisationId, String level) {}
    private record Household(UUID id, UUID organisationId, UUID cellId) {}
}
