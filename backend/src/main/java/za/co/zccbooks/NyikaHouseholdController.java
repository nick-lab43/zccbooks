package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.Date;
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
@RequestMapping("/api/nyikas/{nyikaId}/households")
public class NyikaHouseholdController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public NyikaHouseholdController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping
    public List<HouseholdDto> list(@PathVariable UUID nyikaId,
            @RequestParam(required = false) String search,
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
            SELECT h.id, h.code, h.name, h.cell_id, cell.name AS cell_name,
                   h.household_head_member_id,
                   head.first_name AS head_first_name, head.last_name AS head_last_name,
                   COUNT(membership.id) AS member_count
            FROM households h
            JOIN organisational_units cell ON cell.id = h.cell_id
            LEFT JOIN members head ON head.id = h.household_head_member_id
            LEFT JOIN member_household_memberships membership
                ON membership.household_id = h.id AND membership.valid_to IS NULL
            WHERE h.organisation_id = ? AND h.active = TRUE
              AND h.cell_id IN (SELECT id FROM descendants)
              AND (?::uuid IS NULL OR h.cell_id = ?::uuid)
              AND (? = '' OR h.name ILIKE ? OR h.code ILIKE ?)
            GROUP BY h.id, h.code, h.name, h.cell_id, cell.name,
                     h.household_head_member_id, head.first_name, head.last_name
            ORDER BY cell.name, h.name
            """, (rs, n) -> new HouseholdDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("code"), rs.getString("name"),
                rs.getObject("cell_id", UUID.class).toString(), rs.getString("cell_name"),
                rs.getObject("household_head_member_id") == null ? null :
                    rs.getObject("household_head_member_id", UUID.class).toString(),
                fullName(rs.getString("head_first_name"), rs.getString("head_last_name")),
                rs.getInt("member_count")
            ), nyika.id(), nyika.organisationId(), cellId, cellId, term, pattern, pattern);
    }

    @GetMapping("/{householdId}")
    public HouseholdProfile profile(@PathVariable UUID nyikaId, @PathVariable UUID householdId) {
        Unit nyika = requireNyika(nyikaId);
        authorization.requireUnitPermission("MEMBERS_VIEW", nyikaId);
        HouseholdDto household = findHousehold(householdId, nyika);
        List<HouseholdMemberDto> members = jdbc.query("""
            SELECT m.id, m.first_name, m.last_name, m.classification_id,
                   c.name AS classification_name, m.elite_number, m.zcc_app_number,
                   m.active, membership.relationship_label, membership.valid_from
            FROM member_household_memberships membership
            JOIN members m ON m.id = membership.member_id
            LEFT JOIN classifications c ON c.id = m.classification_id
                AND c.organisation_id = m.organisation_id
            WHERE membership.household_id = ? AND membership.organisation_id = ?
              AND membership.valid_to IS NULL
            ORDER BY m.last_name, m.first_name
            """, (rs, n) -> new HouseholdMemberDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("first_name"),
                rs.getString("last_name"), rs.getObject("classification_id") == null ? null :
                    rs.getObject("classification_id", UUID.class).toString(),
                rs.getString("classification_name"), rs.getString("elite_number"),
                rs.getString("zcc_app_number"), rs.getBoolean("active"),
                rs.getString("relationship_label"), rs.getDate("valid_from").toLocalDate().toString()
            ), householdId, nyika.organisationId());
        return new HouseholdProfile(household, members);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<HouseholdProfile> create(@PathVariable UUID nyikaId,
            @Valid @RequestBody CreateHousehold request) {
        Unit nyika = requireNyika(nyikaId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", nyikaId);
        Cell cell = requireCellInNyika(request.cellId(), nyika);
        if (request.members().isEmpty()) {
            throw new IllegalArgumentException("Select at least one member for the household.");
        }
        List<HouseholdMemberInput> selected = distinctMembers(request.members());
        if (request.headMemberId() != null &&
                selected.stream().noneMatch(item -> item.memberId().equals(request.headMemberId()))) {
            throw new IllegalArgumentException("The household head must be one of the selected members.");
        }

        UUID householdId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO households (id, organisation_id, cell_id, cell_level, code, name)
            VALUES (?, ?, ?, 'CELL', ?, ?)
            """, householdId, nyika.organisationId(), cell.id(), request.code().trim(), request.name().trim());

        for (HouseholdMemberInput member : selected) {
            lockAndValidateMember(member.memberId(), cell, nyika);
            insertMembership(householdId, nyika.organisationId(), member);
        }
        if (request.headMemberId() != null) {
            int updated = jdbc.update("""
                UPDATE households SET household_head_member_id = ?
                WHERE id = ? AND organisation_id = ?
                """, request.headMemberId(), householdId, nyika.organisationId());
            if (updated != 1) throw new IllegalStateException("Household changed while being created.");
        }

        audit.record("HOUSEHOLD_CREATED", "HOUSEHOLD", householdId,
            Map.of("nyikaId", nyika.id().toString(), "cellId", cell.id().toString(),
                "memberCount", Integer.toString(selected.size()),
                "headMemberId", request.headMemberId() == null ? "" : request.headMemberId().toString()));
        HouseholdDto created = findHousehold(householdId, nyika);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new HouseholdProfile(created, loadMembers(householdId, nyika.organisationId())));
    }

    @PostMapping("/{householdId}/members")
    @Transactional
    public HouseholdProfile addMembers(@PathVariable UUID nyikaId, @PathVariable UUID householdId,
            @Valid @RequestBody AddHouseholdMembers request) {
        Unit nyika = requireNyika(nyikaId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", nyikaId);
        HouseholdDto household = findHouseholdForUpdate(householdId, nyika);
        Cell cell = requireCellInNyika(UUID.fromString(household.cellId()), nyika);
        List<HouseholdMemberInput> selected = distinctMembers(request.members());
        for (HouseholdMemberInput member : selected) {
            lockAndValidateMember(member.memberId(), cell, nyika);
            insertMembership(householdId, nyika.organisationId(), member);
        }
        audit.record("HOUSEHOLD_MEMBERS_ADDED", "HOUSEHOLD", householdId,
            Map.of("nyikaId", nyika.id().toString(), "memberCount", Integer.toString(selected.size())));
        return new HouseholdProfile(findHousehold(householdId, nyika),
            loadMembers(householdId, nyika.organisationId()));
    }

    @DeleteMapping("/{householdId}/members/{memberId}")
    @Transactional
    public HouseholdProfile removeMember(@PathVariable UUID nyikaId, @PathVariable UUID householdId,
            @PathVariable UUID memberId) {
        Unit nyika = requireNyika(nyikaId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", nyikaId);
        findHouseholdForUpdate(householdId, nyika);
        UUID headId = jdbc.queryForObject("""
            SELECT household_head_member_id FROM households
            WHERE id = ? AND organisation_id = ? FOR UPDATE
            """, UUID.class, householdId, nyika.organisationId());
        if (memberId.equals(headId)) {
            throw new IllegalArgumentException("Assign a different household head or clear the current head before removing this member.");
        }
        int updated = jdbc.update("""
            UPDATE member_household_memberships SET valid_to = ?
            WHERE household_id = ? AND member_id = ? AND organisation_id = ? AND valid_to IS NULL
            """, Date.valueOf(LocalDate.now()), householdId, memberId, nyika.organisationId());
        if (updated != 1) throw new ResourceNotFoundException("Active household membership not found.");
        audit.record("HOUSEHOLD_MEMBER_REMOVED", "HOUSEHOLD", householdId,
            Map.of("nyikaId", nyika.id().toString(), "memberId", memberId.toString()));
        return new HouseholdProfile(findHousehold(householdId, nyika),
            loadMembers(householdId, nyika.organisationId()));
    }

    @PutMapping("/{householdId}/head")
    @Transactional
    public HouseholdProfile setHead(@PathVariable UUID nyikaId, @PathVariable UUID householdId,
            @Valid @RequestBody SetHouseholdHead request) {
        Unit nyika = requireNyika(nyikaId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", nyikaId);
        findHouseholdForUpdate(householdId, nyika);
        if (request.memberId() != null) {
            Integer eligible = jdbc.queryForObject("""
                SELECT COUNT(*) FROM member_household_memberships membership
                JOIN members m ON m.id = membership.member_id
                WHERE membership.household_id = ? AND membership.member_id = ?
                  AND membership.organisation_id = ? AND membership.valid_to IS NULL
                  AND m.active = TRUE
                """, Integer.class, householdId, request.memberId(), nyika.organisationId());
            if (eligible == null || eligible == 0) {
                throw new IllegalArgumentException("The household head must be an active member of this household.");
            }
        }
        jdbc.update("""
            UPDATE households SET household_head_member_id = ?
            WHERE id = ? AND organisation_id = ?
            """, request.memberId(), householdId, nyika.organisationId());
        audit.record("HOUSEHOLD_HEAD_CHANGED", "HOUSEHOLD", householdId,
            Map.of("nyikaId", nyika.id().toString(),
                "headMemberId", request.memberId() == null ? "" : request.memberId().toString()));
        return new HouseholdProfile(findHousehold(householdId, nyika),
            loadMembers(householdId, nyika.organisationId()));
    }

    private void lockAndValidateMember(UUID memberId, Cell cell, Unit nyika) {
        MemberPlacement member = jdbc.query("""
            SELECT id, cell_id, active FROM members
            WHERE id = ? AND organisation_id = ? AND branch_id IS NULL
            FOR UPDATE
            """, (rs, n) -> new MemberPlacement(rs.getObject("id", UUID.class),
                rs.getObject("cell_id", UUID.class), rs.getBoolean("active")),
            memberId, nyika.organisationId()).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Member not found in this organisation."));
        if (!member.active()) throw new IllegalArgumentException("Inactive members cannot be added to a household.");
        if (!cell.id().equals(member.cellId())) {
            throw new IllegalArgumentException("Every household member must belong to the household's Cell.");
        }
        Integer existing = jdbc.queryForObject("""
            SELECT COUNT(*) FROM member_household_memberships
            WHERE member_id = ? AND organisation_id = ? AND valid_to IS NULL
            """, Integer.class, memberId, nyika.organisationId());
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("A selected member already belongs to an active household.");
        }
    }

    private void insertMembership(UUID householdId, UUID organisationId, HouseholdMemberInput member) {
        jdbc.update("""
            INSERT INTO member_household_memberships
                (id, organisation_id, household_id, member_id, relationship_label, valid_from)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), organisationId, householdId, member.memberId(),
            member.relationshipLabel() == null || member.relationshipLabel().isBlank()
                ? "Member" : member.relationshipLabel().trim(), Date.valueOf(LocalDate.now()));
    }

    private List<HouseholdMemberInput> distinctMembers(List<HouseholdMemberInput> members) {
        if (members == null || members.isEmpty()) throw new IllegalArgumentException("Select at least one member.");
        if (members.size() > 200) throw new IllegalArgumentException("A household cannot be created with more than 200 selected members at once.");
        var seen = new java.util.HashSet<UUID>();
        for (HouseholdMemberInput member : members) {
            if (!seen.add(member.memberId())) throw new IllegalArgumentException("A member can only be selected once.");
        }
        return List.copyOf(members);
    }

    private HouseholdDto findHousehold(UUID householdId, Unit nyika) {
        return householdQuery(householdId, nyika, false);
    }

    private HouseholdDto findHouseholdForUpdate(UUID householdId, Unit nyika) {
        return householdQuery(householdId, nyika, true);
    }

    private HouseholdDto householdQuery(UUID householdId, Unit nyika, boolean lock) {
        String sql = """
            WITH RECURSIVE descendants AS (
                SELECT id FROM organisational_units WHERE id = ?
                UNION ALL
                SELECT child.id FROM organisational_units child
                JOIN descendants parent ON child.parent_id = parent.id
            )
            SELECT h.id, h.code, h.name, h.cell_id, cell.name AS cell_name,
                   h.household_head_member_id, head.first_name AS head_first_name,
                   head.last_name AS head_last_name,
                   (SELECT COUNT(*) FROM member_household_memberships x
                    WHERE x.household_id = h.id AND x.valid_to IS NULL) AS member_count
            FROM households h
            JOIN organisational_units cell ON cell.id = h.cell_id
            LEFT JOIN members head ON head.id = h.household_head_member_id
            WHERE h.id = ? AND h.organisation_id = ? AND h.active = TRUE
              AND h.cell_id IN (SELECT id FROM descendants)
            """ + (lock ? " FOR UPDATE OF h" : "");
        return jdbc.query(sql, (rs, n) -> new HouseholdDto(
            rs.getObject("id", UUID.class).toString(), rs.getString("code"), rs.getString("name"),
            rs.getObject("cell_id", UUID.class).toString(), rs.getString("cell_name"),
            rs.getObject("household_head_member_id") == null ? null :
                rs.getObject("household_head_member_id", UUID.class).toString(),
            fullName(rs.getString("head_first_name"), rs.getString("head_last_name")),
            rs.getInt("member_count")
        ), nyika.id(), householdId, nyika.organisationId()).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Household not found in this Nyika."));
    }

    private List<HouseholdMemberDto> loadMembers(UUID householdId, UUID organisationId) {
        return jdbc.query("""
            SELECT m.id, m.first_name, m.last_name, m.classification_id,
                   c.name AS classification_name, m.elite_number, m.zcc_app_number,
                   m.active, membership.relationship_label, membership.valid_from
            FROM member_household_memberships membership
            JOIN members m ON m.id = membership.member_id
            LEFT JOIN classifications c ON c.id = m.classification_id
                AND c.organisation_id = m.organisation_id
            WHERE membership.household_id = ? AND membership.organisation_id = ?
              AND membership.valid_to IS NULL
            ORDER BY m.last_name, m.first_name
            """, (rs, n) -> new HouseholdMemberDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("first_name"),
                rs.getString("last_name"), rs.getObject("classification_id") == null ? null :
                    rs.getObject("classification_id", UUID.class).toString(),
                rs.getString("classification_name"), rs.getString("elite_number"),
                rs.getString("zcc_app_number"), rs.getBoolean("active"),
                rs.getString("relationship_label"), rs.getDate("valid_from").toLocalDate().toString()
            ), householdId, organisationId);
    }

    private Unit requireNyika(UUID id) {
        Unit unit = jdbc.query("""
            SELECT id, organisation_id, level, name FROM organisational_units
            WHERE id = ? AND active = TRUE
            """, (rs, n) -> new Unit(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getString("level"), rs.getString("name")), id)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Active Nyika not found."));
        if (!"NYIKA".equals(unit.level())) throw new IllegalArgumentException("The selected unit is not a Nyika.");
        return unit;
    }

    private Cell requireCellInNyika(UUID cellId, Unit nyika) {
        return jdbc.query("""
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
            .filter(cell -> "CELL".equals(cell.level()) && nyika.organisationId().equals(cell.organisationId()))
            .orElseThrow(() -> new IllegalArgumentException("Select an active Cell within your Nyika."));
    }

    private static String fullName(String first, String last) {
        if (first == null && last == null) return null;
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
    }

    public record CreateHousehold(@NotNull UUID cellId, @NotBlank @Size(max = 60) String code,
            @NotBlank @Size(max = 160) String name, @NotEmpty @Size(max = 200) List<HouseholdMemberInput> members,
            UUID headMemberId) {}
    public record AddHouseholdMembers(@NotEmpty @Size(max = 200) List<HouseholdMemberInput> members) {}
    public record HouseholdMemberInput(@NotNull UUID memberId, @Size(max = 80) String relationshipLabel) {}
    public record SetHouseholdHead(UUID memberId) {}
    public record HouseholdDto(String id, String code, String name, String cellId, String cellName,
            String headMemberId, String headMemberName, int memberCount) {}
    public record HouseholdProfile(HouseholdDto household, List<HouseholdMemberDto> members) {}
    public record HouseholdMemberDto(String id, String firstName, String lastName, String classificationId,
            String classificationName, String eliteNumber, String zccAppNumber, boolean active,
            String relationshipLabel, String joinedOn) {}
    private record Unit(UUID id, UUID organisationId, String level, String name) {}
    private record Cell(UUID id, UUID organisationId, String level, String name) {}
    private record MemberPlacement(UUID id, UUID cellId, boolean active) {}
}
