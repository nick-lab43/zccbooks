package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
    public List<NyikaMemberDto> members(@PathVariable UUID nyikaId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID classificationId,
            @RequestParam(required = false) UUID cellId) {
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
            SELECT m.id, m.zcc_app_number, m.elite_number, m.first_name, m.last_name,
                   m.classification_id, classification.name AS classification_name,
                   m.active, m.cell_id, cell.name AS cell_name
            FROM members m
            LEFT JOIN classifications classification ON classification.id = m.classification_id
                AND classification.organisation_id = m.organisation_id
            LEFT JOIN organisational_units cell ON cell.id = m.cell_id
            WHERE m.organisation_id = ?
              AND ((m.cell_id IS NULL AND m.nyika_id = ?) OR m.cell_id IN (SELECT id FROM descendants))
              AND m.branch_id IS NULL
              AND (? = '' OR m.first_name ILIKE ? OR m.last_name ILIKE ?
                   OR COALESCE(m.zcc_app_number, '') ILIKE ?
                   OR COALESCE(m.elite_number, '') ILIKE ?)
              AND (?::uuid IS NULL OR m.classification_id = ?::uuid)
              AND (?::uuid IS NULL OR m.cell_id = ?::uuid)
            ORDER BY m.last_name, m.first_name
            """, (rs, n) -> new NyikaMemberDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("zcc_app_number"),
                rs.getString("elite_number"), rs.getString("first_name"), rs.getString("last_name"),
                rs.getObject("classification_id") == null ? null : rs.getObject("classification_id", UUID.class).toString(),
                rs.getString("classification_name"), rs.getBoolean("active"),
                rs.getObject("cell_id") == null ? null : rs.getObject("cell_id", UUID.class).toString(),
                rs.getString("cell_name")
            ), nyika.id(), nyika.organisationId(), nyika.id(), term, pattern, pattern, pattern, pattern,
                classificationId, classificationId, cellId, cellId);
    }

    @PostMapping("/nyikas/{nyikaId}/members")
    @Transactional
    public ResponseEntity<NyikaMemberDto> createMember(@PathVariable UUID nyikaId,
            @Valid @RequestBody CreateNyikaMember request) {
        Unit nyika = requireNyika(nyikaId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", nyikaId);

        String classificationName = jdbc.query("""
            SELECT name FROM classifications
            WHERE id = ? AND organisation_id = ? AND active = TRUE
            """, (rs, n) -> rs.getString("name"), request.classificationId(), nyika.organisationId())
            .stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Select an active classification configured for this organisation by Central Administration."));

        UUID cellId = request.cellId();
        String cellName = null;
        if (cellId != null) {
            Cell cell = requireCellInNyika(cellId, nyika);
            authorization.requireUnitPermission("MEMBERS_MANAGE", cellId);
            cellName = cell.name();
        }

        UUID positionId = request.positionId();
        UUID roleScopeId = cellId == null ? nyika.id() : cellId;
        PositionAssignment selectedRole = null;
        if (positionId != null) {
            selectedRole = jdbc.query("""
                SELECT id, name, allow_concurrent, active
                FROM church_positions WHERE id = ?
                """, (rs, n) -> new PositionAssignment(
                    rs.getObject("id", UUID.class), rs.getString("name"),
                    rs.getBoolean("allow_concurrent"), rs.getBoolean("active")), positionId)
                .stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Church role not found."));
            if (!position.active()) throw new IllegalArgumentException("Select an active church role configured by Central Administration.");
            authorization.requireUnitPermission("MEMBERS_MANAGE", roleScopeId);
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO members
                (id, branch_id, organisation_id, nyika_id, cell_id, cell_level,
                 zcc_app_number, elite_number, first_name, last_name, classification_id)
            VALUES (?, NULL, ?, ?, ?, 'CELL', ?, ?, ?, ?, ?)
            """, id, nyika.organisationId(), nyika.id(), cellId, blankToNull(request.zccAppNumber()),
                blankToNull(request.eliteNumber()), request.firstName().trim(), request.lastName().trim(),
                request.classificationId());

        audit.record("MEMBER_REGISTERED", "MEMBER", id,
            Map.of("organisationId", nyika.organisationId().toString(),
                "nyikaId", nyika.id().toString(),
                "cellId", cellId == null ? "" : cellId.toString(),
                "classificationId", request.classificationId().toString(),
                "eliteNumber", blankToNull(request.eliteNumber()) == null ? "" : blankToNull(request.eliteNumber())));

        if (positionId != null) {
            String overlapSql = """
                SELECT COUNT(*) FROM member_appointments
                WHERE organisation_id = ? AND member_id = ? AND position_id = ?
                  AND valid_to IS NULL
                """ + (selectedRole.allowConcurrent() ? " AND scope_unit_id = ?" : "");
            Integer overlap = selectedRole.allowConcurrent()
                ? jdbc.queryForObject(overlapSql, Integer.class, nyika.organisationId(), id, positionId, roleScopeId)
                : jdbc.queryForObject(overlapSql, Integer.class, nyika.organisationId(), id, positionId);
            if (overlap != null && overlap > 0) throw new ConflictException("This member already holds this church role.");
            UUID actorId = jdbc.queryForObject(
                "SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
                UUID.class, authorization.currentUsername());
            UUID assignmentId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO member_appointments
                    (id, organisation_id, member_id, position_id, scope_unit_id, valid_from, valid_to, note, recorded_by)
                VALUES (?, ?, ?, ?, ?, CURRENT_DATE, NULL, NULL, ?)
                """, assignmentId, nyika.organisationId(), id, positionId, roleScopeId, actorId);
            audit.record("MEMBER_APPOINTMENT_CREATED", "MEMBER_APPOINTMENT", assignmentId,
                Map.of("memberId", id.toString(), "positionId", positionId.toString(),
                    "scopeUnitId", roleScopeId.toString(), "validFrom", java.time.LocalDate.now().toString(),
                    "assignmentSource", "MEMBER_REGISTRATION"));
        }

        NyikaMemberDto created = new NyikaMemberDto(id.toString(), blankToNull(request.zccAppNumber()),
            blankToNull(request.eliteNumber()), request.firstName().trim(), request.lastName().trim(),
            request.classificationId().toString(), classificationName, true,
            cellId == null ? null : cellId.toString(), cellName);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/nyikas/{nyikaId}/members/{memberId}")
    public NyikaMemberDto memberProfile(@PathVariable UUID nyikaId, @PathVariable UUID memberId) {
        Unit nyika = requireNyika(nyikaId);
        authorization.requireUnitPermission("MEMBERS_VIEW", nyikaId);
        return findMemberInNyika(memberId, nyika);
    }

    private NyikaMemberDto findMemberInNyika(UUID memberId, Unit nyika) {
        return jdbc.query("""
            WITH RECURSIVE descendants AS (
                SELECT id FROM organisational_units WHERE id = ?
                UNION ALL
                SELECT child.id FROM organisational_units child
                JOIN descendants parent ON child.parent_id = parent.id
            )
            SELECT m.id, m.zcc_app_number, m.elite_number, m.first_name, m.last_name,
                   m.classification_id, c.name AS classification_name, m.active,
                   m.cell_id, cell.name AS cell_name
            FROM members m
            LEFT JOIN classifications c ON c.id = m.classification_id
                AND c.organisation_id = m.organisation_id
            LEFT JOIN organisational_units cell ON cell.id = m.cell_id
            WHERE m.id = ? AND m.organisation_id = ? AND m.branch_id IS NULL
              AND ((m.cell_id IS NULL AND m.nyika_id = ?) OR m.cell_id IN (SELECT id FROM descendants))
            """, (rs, n) -> new NyikaMemberDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("zcc_app_number"),
                rs.getString("elite_number"), rs.getString("first_name"), rs.getString("last_name"),
                rs.getObject("classification_id") == null ? null : rs.getObject("classification_id", UUID.class).toString(),
                rs.getString("classification_name"), rs.getBoolean("active"),
                rs.getObject("cell_id") == null ? null : rs.getObject("cell_id", UUID.class).toString(),
                rs.getString("cell_name")
            ), nyika.id(), memberId, nyika.organisationId(), nyika.id())
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Member not found in this Nyika."));
    }

    @PutMapping("/nyikas/{nyikaId}/members/{memberId}")
    @Transactional
    public NyikaMemberDto updateMember(@PathVariable UUID nyikaId, @PathVariable UUID memberId,
            @Valid @RequestBody UpdateNyikaMember request) {
        Unit nyika = requireNyika(nyikaId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", nyikaId);
        NyikaMemberDto current = findMemberInNyika(memberId, nyika);

        String classificationName = jdbc.query("""
            SELECT name FROM classifications
            WHERE id = ? AND organisation_id = ? AND active = TRUE
            """, (rs, n) -> rs.getString("name"), request.classificationId(), nyika.organisationId())
            .stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Select an active classification configured for this organisation."));

        UUID nextCellId = request.cellId();
        String nextCellName = null;
        if (nextCellId != null) {
            Cell cell = requireCellInNyika(nextCellId, nyika);
            authorization.requireUnitPermission("MEMBERS_MANAGE", nextCellId);
            nextCellName = cell.name();
        }

        if (!java.util.Objects.equals(current.cellId(), nextCellId == null ? null : nextCellId.toString())) {
            Integer householdMemberships = jdbc.queryForObject("""
                SELECT COUNT(*) FROM member_household_memberships
                WHERE member_id = ? AND organisation_id = ? AND valid_to IS NULL
                """, Integer.class, memberId, nyika.organisationId());
            if (householdMemberships != null && householdMemberships > 0) {
                throw new IllegalArgumentException("Remove this member from their active household before changing their Cell.");
            }
        }

        int updated = jdbc.update("""
            UPDATE members
            SET first_name = ?, last_name = ?, zcc_app_number = ?, elite_number = ?,
                classification_id = ?, cell_id = ?, cell_level = 'CELL'
            WHERE id = ? AND organisation_id = ? AND branch_id IS NULL
              AND ((cell_id IS NULL AND nyika_id = ?) OR cell_id IN (
                  WITH RECURSIVE descendants AS (
                      SELECT id FROM organisational_units WHERE id = ?
                      UNION ALL
                      SELECT child.id FROM organisational_units child
                      JOIN descendants parent ON child.parent_id = parent.id
                  ) SELECT id FROM descendants
              ))
            """, request.firstName().trim(), request.lastName().trim(), blankToNull(request.zccAppNumber()),
                blankToNull(request.eliteNumber()), request.classificationId(), nextCellId,
                memberId, nyika.organisationId(), nyika.id(), nyika.id());
        if (updated != 1) throw new IllegalStateException("Member record changed during this update. Reload and retry.");

        audit.record("MEMBER_UPDATED", "MEMBER", memberId,
            Map.of("nyikaId", nyika.id().toString(),
                "classificationId", request.classificationId().toString(),
                "cellId", nextCellId == null ? "" : nextCellId.toString(),
                "eliteNumber", blankToNull(request.eliteNumber()) == null ? "" : blankToNull(request.eliteNumber())));

        return new NyikaMemberDto(memberId.toString(), blankToNull(request.zccAppNumber()),
            blankToNull(request.eliteNumber()), request.firstName().trim(), request.lastName().trim(),
            request.classificationId().toString(), classificationName, current.active(),
            nextCellId == null ? null : nextCellId.toString(), nextCellName);
    }

    @PostMapping("/nyikas/{nyikaId}/members/{memberId}/status")
    @Transactional
    public NyikaMemberDto setMemberStatus(@PathVariable UUID nyikaId, @PathVariable UUID memberId,
            @Valid @RequestBody SetMemberStatus request) {
        Unit nyika = requireNyika(nyikaId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", nyikaId);
        NyikaMemberDto current = memberProfile(nyikaId, memberId);
        int updated = jdbc.update("""
            UPDATE members SET active = ?
            WHERE id = ? AND organisation_id = ? AND branch_id IS NULL
            """, request.active(), memberId, nyika.organisationId());
        if (updated != 1) throw new IllegalStateException("Member record changed during this operation. Reload and retry.");
        audit.record(request.active() ? "MEMBER_REACTIVATED" : "MEMBER_DEACTIVATED",
            "MEMBER", memberId, Map.of("nyikaId", nyika.id().toString()));
        return new NyikaMemberDto(current.id(), current.zccAppNumber(), current.eliteNumber(),
            current.firstName(), current.lastName(), current.classificationId(), current.classificationName(),
            request.active(), current.cellId(), current.cellName());
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
            String eliteNumber,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotNull UUID classificationId, UUID cellId, UUID positionId) {}
    public record UpdateNyikaMember(@NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName, @Size(max = 80) String zccAppNumber,
            String eliteNumber, @NotNull UUID classificationId, UUID cellId) {}
    public record SetMemberStatus(@NotNull Boolean active) {}
    public record BulkAssignMembers(@jakarta.validation.constraints.NotEmpty @Size(max = 500) List<UUID> memberIds) {}
    public record BulkAssignmentResult(int assignedCount, String cellId) {}
    public record NyikaMemberDto(String id, String zccAppNumber, String eliteNumber,
            String firstName, String lastName, String classificationId, String classificationName,
            boolean active, String cellId, String cellName) {}
    private record Unit(UUID id, UUID organisationId, String level, String name) {}
    private record Cell(UUID id, UUID organisationId, String level, String name) {}
    private record PositionAssignment(UUID id, String name, boolean allowConcurrent, boolean active) {}
}
