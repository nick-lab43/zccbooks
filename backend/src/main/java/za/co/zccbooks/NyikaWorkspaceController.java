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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class NyikaWorkspaceController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public NyikaWorkspaceController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/nyikas/{nyikaId}/members")
    public List<NyikaMemberDto> members(@PathVariable UUID nyikaId, @RequestParam(required = false) String search) {
        Unit nyika = requireNyika(nyikaId);
        authorization.requireUnitPermission("MEMBERS_VIEW", nyikaId);
        String term = search == null ? "" : search.trim();
        String pattern = "%" + term + "%";
        return jdbc.query("""
            WITH RECURSIVE descendants AS (
                SELECT id FROM organisational_units WHERE id = ?
                UNION ALL
                SELECT child.id FROM organisational_units child
                JOIN descendants parent ON child.parent_id = parent.id
            )
            SELECT m.id, m.zcc_app_number, m.first_name, m.last_name, m.active,
                   m.cell_id, cell.name AS cell_name
            FROM members m
            LEFT JOIN organisational_units cell ON cell.id = m.cell_id
            WHERE m.organisation_id = ?
              AND (m.cell_id IS NULL OR m.cell_id IN (SELECT id FROM descendants))
              AND m.branch_id IS NULL
              AND (? = '' OR m.first_name ILIKE ? OR m.last_name ILIKE ?
                   OR COALESCE(m.zcc_app_number, '') ILIKE ?)
            ORDER BY m.last_name, m.first_name
            """, (rs, n) -> new NyikaMemberDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("zcc_app_number"),
                rs.getString("first_name"), rs.getString("last_name"), rs.getBoolean("active"),
                rs.getObject("cell_id") == null ? null : rs.getObject("cell_id", UUID.class).toString(),
                rs.getString("cell_name")
            ), nyika.id(), nyika.organisationId(), term, pattern, pattern, pattern);
    }

    @PostMapping("/nyikas/{nyikaId}/members")
    @Transactional
    public ResponseEntity<NyikaMemberDto> createMember(@PathVariable UUID nyikaId,
            @Valid @RequestBody CreateNyikaMember request) {
        Unit nyika = requireNyika(nyikaId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", nyikaId);
        UUID cellId = request.cellId();
        String cellName = null;
        if (cellId != null) {
            Cell cell = requireCellInNyika(cellId, nyika);
            authorization.requireUnitPermission("MEMBERS_MANAGE", cellId);
            cellName = cell.name();
        }
        UUID id = UUID.randomUUID();
        NyikaMemberDto created = jdbc.queryForObject("""
            INSERT INTO members
                (id, branch_id, organisation_id, cell_id, cell_level, zcc_app_number,
                 first_name, last_name)
            VALUES (?, NULL, ?, ?, 'CELL', ?, ?, ?)
            RETURNING id, zcc_app_number, first_name, last_name, active, cell_id
            """, (rs, n) -> new NyikaMemberDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("zcc_app_number"),
                rs.getString("first_name"), rs.getString("last_name"), rs.getBoolean("active"),
                rs.getObject("cell_id") == null ? null : rs.getObject("cell_id", UUID.class).toString(),
                cellName
            ), id, nyika.organisationId(), cellId, blankToNull(request.zccAppNumber()),
                request.firstName().trim(), request.lastName().trim());
        audit.record("MEMBER_REGISTERED", "MEMBER", id,
            Map.of("organisationId", nyika.organisationId().toString(), "nyikaId", nyika.id().toString(),
                "cellId", cellId == null ? "" : cellId.toString()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/units/{cellId}/members/bulk-assign")
    @Transactional
    public ResponseEntity<BulkAssignmentResult> bulkAssign(@PathVariable UUID cellId,
            @Valid @RequestBody BulkAssignMembers request) {
        Unit cellUnit = requireUnit(cellId);
        if (!"CELL".equals(cellUnit.level())) throw new IllegalArgumentException("Destination must be a Cell.");
        Unit nyika = findNyika(cellId);
        if (!authorization.isCentralAdministrator()) authorization.requireNyikaAdministratorFor(cellId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", cellId);
        if (request.memberIds().size() > 500) throw new IllegalArgumentException("Select no more than 500 members at a time.");
        int assigned = 0;
        for (UUID memberId : request.memberIds().stream().distinct().toList()) {
            Integer eligible = jdbc.queryForObject("""
                SELECT COUNT(*) FROM members
                WHERE id = ? AND organisation_id = ? AND branch_id IS NULL AND cell_id IS NULL AND active = TRUE
                """, Integer.class, memberId, cellUnit.organisationId());
            if (eligible == null || eligible == 0) {
                throw new IllegalArgumentException("One or more selected members are already assigned, inactive or outside this organisation. Reload the register.");
            }
            int updated = jdbc.update("""
                UPDATE members SET cell_id = ?, cell_level = 'CELL'
                WHERE id = ? AND organisation_id = ? AND branch_id IS NULL AND cell_id IS NULL AND active = TRUE
                """, cellId, memberId, cellUnit.organisationId());
            if (updated != 1) throw new IllegalStateException("A selected member changed during assignment. Reload and retry.");
            audit.record("MEMBER_ASSIGNED_TO_CELL", "MEMBER", memberId,
                Map.of("organisationId", cellUnit.organisationId().toString(),
                    "nyikaId", nyika.id().toString(), "cellId", cellId.toString()));
            assigned++;
        }
        return ResponseEntity.ok(new BulkAssignmentResult(assigned, cellId.toString()));
    }

    private Unit requireNyika(UUID id) {
        Unit unit = requireUnit(id);
        if (!"NYIKA".equals(unit.level())) throw new IllegalArgumentException("The selected geographical unit is not a Nyika.");
        return unit;
    }

    private Unit requireUnit(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, level, name FROM organisational_units WHERE id = ? AND active = TRUE
            """, (rs, n) -> new Unit(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getString("level"), rs.getString("name")), id)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Active geographical unit not found."));
    }

    private Cell requireCellInNyika(UUID cellId, Unit nyika) {
        Cell cell = jdbc.query("""
            WITH RECURSIVE descendants AS (
                SELECT id FROM organisational_units WHERE id = ?
                UNION ALL
                SELECT child.id FROM organisational_units child
                JOIN descendants parent ON child.parent_id = parent.id
            )
            SELECT id, organisation_id, level, name FROM organisational_units
            WHERE id = ? AND id IN (SELECT id FROM descendants) AND active = TRUE
            """, (rs, n) -> new Cell(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getString("level"), rs.getString("name")),
            nyika.id(), cellId).stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Select an active Cell within your Nyika."));
        if (!"CELL".equals(cell.level()) || !nyika.organisationId().equals(cell.organisationId())) {
            throw new IllegalArgumentException("Select an active Cell within your Nyika.");
        }
        return cell;
    }

    private Unit findNyika(UUID unitId) {
        return jdbc.query("""
            WITH RECURSIVE ancestors AS (
                SELECT id, organisation_id, parent_id, level, name
                FROM organisational_units WHERE id = ?
                UNION ALL
                SELECT parent.id, parent.organisation_id, parent.parent_id, parent.level, parent.name
                FROM organisational_units parent
                JOIN ancestors child ON child.parent_id = parent.id
                    AND child.organisation_id = parent.organisation_id
            )
            SELECT id, organisation_id, level, name FROM ancestors WHERE level = 'NYIKA'
            """, (rs, n) -> new Unit(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getString("level"), rs.getString("name")),
            unitId).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Cell is not inside a Nyika."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreateNyikaMember(@Size(max = 80) String zccAppNumber,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName, UUID cellId) {}
    public record BulkAssignMembers(@jakarta.validation.constraints.NotEmpty @Size(max = 500) List<UUID> memberIds) {}
    public record BulkAssignmentResult(int assignedCount, String cellId) {}
    public record NyikaMemberDto(String id, String zccAppNumber, String firstName, String lastName,
            boolean active, String cellId, String cellName) {}
    private record Unit(UUID id, UUID organisationId, String level, String name) {}
    private record Cell(UUID id, UUID organisationId, String level, String name) {}
}
