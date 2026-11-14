package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class OrganisationalUnitController {
    private static final List<String> LEVELS = List.of(
        "COUNTRY", "PROVINCE_STATE", "DISTRICT", "NYIKA", "TABHERA", "CELL"
    );

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;

    public OrganisationalUnitController(JdbcTemplate jdbc, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    @GetMapping("/organisations/{organisationId}/units")
    public List<UnitDto> units(@PathVariable UUID organisationId) {
        requireOrganisation(organisationId);
        authorization.requireOrganisationOrUnitPermission("ORG_VIEW", organisationId);
        String sql = """
            SELECT id, organisation_id, parent_id, level, code, name, active
            FROM organisational_units WHERE organisation_id = ?
            ORDER BY level, name
            """;
        Object[] parameters = new Object[] { organisationId };
        if (!authorization.hasOrganisationWidePermission("ORG_VIEW", organisationId)) {
            sql = """
                WITH RECURSIVE visible_units AS (
                    SELECT ou.id, ou.organisation_id, ou.parent_id
                    FROM organisational_units ou
                    JOIN admin_role_assignments a ON a.scope_unit_id = ou.id
                    JOIN admin_users u ON u.id = a.user_id
                    JOIN admin_role_permissions rp ON rp.role_id = a.role_id
                    WHERE ou.organisation_id = ?
                      AND a.organisation_id = ?
                      AND a.scope_type = 'UNIT'
                      AND a.revoked_at IS NULL
                      AND LOWER(u.username) = LOWER(?)
                      AND rp.permission_code = 'ORG_VIEW'
                    UNION
                    SELECT child.id, child.organisation_id, child.parent_id
                    FROM organisational_units child
                    JOIN visible_units parent
                      ON child.parent_id = parent.id
                     AND child.organisation_id = parent.organisation_id
                )
                SELECT ou.id, ou.organisation_id, ou.parent_id, ou.level, ou.code, ou.name, ou.active
                FROM organisational_units ou
                JOIN visible_units v ON v.id = ou.id
                ORDER BY ou.level, ou.name
                """;
            parameters = new Object[] { organisationId, organisationId, authorization.currentUsername() };
        }
        return jdbc.query(sql, (rs, n) -> new UnitDto(
            rs.getObject("id", UUID.class).toString(),
            rs.getObject("organisation_id", UUID.class).toString(),
            rs.getObject("parent_id") == null ? null : rs.getObject("parent_id", UUID.class).toString(),
            rs.getString("level"), rs.getString("code"), rs.getString("name"), rs.getBoolean("active")
        ), parameters);
    }

    @GetMapping("/units/{parentId}/children")
    public List<UnitDto> children(@PathVariable UUID parentId) {
        UnitRow parent = requireUnit(parentId);
        authorization.requireUnitPermission("ORG_VIEW", parentId);
        return jdbc.query("""
            SELECT id, organisation_id, parent_id, level, code, name, active
            FROM organisational_units WHERE organisation_id = ? AND parent_id = ?
            ORDER BY name
            """, (rs, n) -> new UnitDto(
                rs.getObject("id", UUID.class).toString(),
                rs.getObject("organisation_id", UUID.class).toString(),
                rs.getObject("parent_id", UUID.class).toString(),
                rs.getString("level"), rs.getString("code"), rs.getString("name"), rs.getBoolean("active")
            ), parent.organisationId(), parent.id());
    }

    @PostMapping("/organisations/{organisationId}/units")
    public ResponseEntity<UnitDto> createCountry(@PathVariable UUID organisationId,
            @Valid @RequestBody CreateUnit request) {
        requireOrganisation(organisationId);
        authorization.requireOrganisationPermission("ORG_MANAGE", organisationId);
        return createUnit(organisationId, null, "COUNTRY", request);
    }

    @PostMapping("/units/{parentId}/children")
    public ResponseEntity<UnitDto> createChild(@PathVariable UUID parentId,
            @Valid @RequestBody CreateUnit request) {
        UnitRow parent = requireUnit(parentId);
        authorization.requireUnitPermission("ORG_MANAGE", parentId);
        int parentIndex = LEVELS.indexOf(parent.level());
        if (parentIndex < 0 || parentIndex == LEVELS.size() - 1) {
            throw new IllegalArgumentException("A Cell is the lowest geographical level. Households are maintained separately.");
        }
        return createUnit(parent.organisationId(), parent.id(), LEVELS.get(parentIndex + 1), request);
    }

    @GetMapping("/units/{cellId}/household-members")
    public List<ActiveHouseholdMemberDto> activeHouseholdMembers(@PathVariable UUID cellId) {
        UnitRow cell = requireCell(cellId);
        authorization.requireUnitPermission("MEMBERS_VIEW", cellId);
        return jdbc.query("""
            SELECT x.member_id
            FROM member_household_memberships x
            JOIN households h ON h.id = x.household_id
            WHERE h.organisation_id = ? AND h.cell_id = ? AND x.valid_to IS NULL
            ORDER BY x.member_id
            """, (rs, n) -> new ActiveHouseholdMemberDto(
                rs.getObject("member_id", UUID.class).toString()
            ), cell.organisationId(), cell.id());
    }

    @GetMapping("/units/{cellId}/households")
    public List<HouseholdDto> households(@PathVariable UUID cellId) {
        UnitRow cell = requireCell(cellId);
        authorization.requireUnitPermission("MEMBERS_VIEW", cellId);
        return jdbc.query("""
            SELECT id, code, name, household_head_member_id, active
            FROM households WHERE organisation_id = ? AND cell_id = ?
            ORDER BY name
            """, (rs, n) -> new HouseholdDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("code"), rs.getString("name"),
                rs.getObject("household_head_member_id") == null ? null :
                    rs.getObject("household_head_member_id", UUID.class).toString(), rs.getBoolean("active")
            ), cell.organisationId(), cell.id());
    }

    @PostMapping("/units/{cellId}/households")
    public ResponseEntity<HouseholdDto> createHousehold(@PathVariable UUID cellId,
            @Valid @RequestBody CreateHousehold request) {
        UnitRow cell = requireCell(cellId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", cellId);
        UUID id = UUID.randomUUID();
        HouseholdDto created = jdbc.queryForObject("""
            INSERT INTO households (id, organisation_id, cell_id, code, name)
            VALUES (?, ?, ?, ?, ?)
            RETURNING id, code, name, household_head_member_id, active
            """, (rs, n) -> new HouseholdDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("code"), rs.getString("name"),
                null, rs.getBoolean("active")
            ), id, cell.organisationId(), cell.id(), request.code().trim(), request.name().trim());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/households/{householdId}/members")
    public List<HouseholdMemberDto> householdMembers(@PathVariable UUID householdId) {
        HouseholdRow household = requireHousehold(householdId);
        authorization.requireUnitPermission("MEMBERS_VIEW", household.cellId());
        return jdbc.query("""
            SELECT m.id, m.zcc_app_number, m.first_name, m.last_name,
                   x.relationship_label, x.valid_from, x.valid_to
            FROM member_household_memberships x
            JOIN members m ON m.id = x.member_id
            WHERE x.household_id = ?
            ORDER BY x.valid_to NULLS FIRST, m.last_name, m.first_name
            """, (rs, n) -> new HouseholdMemberDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("zcc_app_number"),
                rs.getString("first_name"), rs.getString("last_name"), rs.getString("relationship_label"),
                rs.getDate("valid_from").toLocalDate().toString(),
                rs.getDate("valid_to") == null ? null : rs.getDate("valid_to").toLocalDate().toString()
            ), householdId);
    }

    @PostMapping("/households/{householdId}/members")
    public ResponseEntity<Void> addHouseholdMember(@PathVariable UUID householdId,
            @Valid @RequestBody AddHouseholdMember request) {
        HouseholdRow household = requireHousehold(householdId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", household.cellId());
        Integer memberCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM members m
            WHERE m.id = ? AND m.organisation_id = ? AND m.cell_id = ?
            """, Integer.class, request.memberId(), household.organisationId(), household.cellId());
        if (memberCount == null || memberCount == 0) {
            throw new ResourceNotFoundException("Member not found in this organisation.");
        }
        jdbc.update("""
            INSERT INTO member_household_memberships
                (id, organisation_id, household_id, member_id, relationship_label, valid_from)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), household.organisationId(), household.id(), request.memberId(),
            request.relationshipLabel().trim(), request.validFrom() == null ? Date.valueOf(java.time.LocalDate.now()) : Date.valueOf(request.validFrom()));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/households/{householdId}/members/{memberId}/end")
    public ResponseEntity<Void> endHouseholdMembership(@PathVariable UUID householdId,
            @PathVariable UUID memberId, @Valid @RequestBody EndHouseholdMembership request) {
        HouseholdRow household = requireHousehold(householdId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", household.cellId());
        int updated = jdbc.update("""
            UPDATE member_household_memberships
            SET valid_to = ?
            WHERE household_id = ? AND member_id = ? AND valid_to IS NULL
            """, Date.valueOf(request.validTo()), householdId, memberId);
        if (updated == 0) {
            throw new ResourceNotFoundException("Active household membership not found.");
        }
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<UnitDto> createUnit(UUID organisationId, UUID parentId, String level, CreateUnit request) {
        UUID id = UUID.randomUUID();
        return ResponseEntity.status(HttpStatus.CREATED).body(jdbc.queryForObject("""
            INSERT INTO organisational_units (id, organisation_id, parent_id, level, code, name)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id, organisation_id, parent_id, level, code, name, active
            """, (rs, n) -> new UnitDto(
                rs.getObject("id", UUID.class).toString(),
                rs.getObject("organisation_id", UUID.class).toString(),
                rs.getObject("parent_id") == null ? null : rs.getObject("parent_id", UUID.class).toString(),
                rs.getString("level"), rs.getString("code"), rs.getString("name"), rs.getBoolean("active")
            ), id, organisationId, parentId, level, request.code().trim(), request.name().trim()));
    }

    private UnitRow requireUnit(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, level FROM organisational_units WHERE id = ?
            """, (rs, n) -> new UnitRow(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getString("level")), id)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Organisational unit not found."));
    }

    private UnitRow requireCell(UUID id) {
        UnitRow unit = requireUnit(id);
        if (!"CELL".equals(unit.level())) throw new IllegalArgumentException("Households must belong to a Cell.");
        return unit;
    }

    private HouseholdRow requireHousehold(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, cell_id FROM households WHERE id = ?
            """, (rs, n) -> new HouseholdRow(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getObject("cell_id", UUID.class)), id)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Household not found."));
    }

    private void requireOrganisation(UUID id) {
        if (jdbc.queryForList("SELECT id FROM organisations WHERE id = ?", UUID.class, id).isEmpty())
            throw new ResourceNotFoundException("Organisation not found.");
    }

    public record CreateUnit(@NotBlank @Size(max = 60) String code, @NotBlank @Size(max = 160) String name) {}
    public record CreateHousehold(@NotBlank @Size(max = 60) String code, @NotBlank @Size(max = 160) String name) {}
    public record AddHouseholdMember(@jakarta.validation.constraints.NotNull UUID memberId,
                                     @NotBlank @Size(max = 80) String relationshipLabel,
                                     java.time.LocalDate validFrom) {}
    public record EndHouseholdMembership(@jakarta.validation.constraints.NotNull java.time.LocalDate validTo) {}
    public record UnitDto(String id, String organisationId, String parentId, String level, String code, String name, boolean active) {}
    public record HouseholdDto(String id, String code, String name, String householdHeadMemberId, boolean active) {}\n    public record ActiveHouseholdMemberDto(String memberId) {}
    public record HouseholdMemberDto(String id, String zccAppNumber, String firstName, String lastName,
                                     String relationshipLabel, String validFrom, String validTo) {}
    private record UnitRow(UUID id, UUID organisationId, String level) {}
    private record HouseholdRow(UUID id, UUID organisationId, UUID cellId) {}
}
