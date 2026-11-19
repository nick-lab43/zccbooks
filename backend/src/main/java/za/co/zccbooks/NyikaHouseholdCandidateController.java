package za.co.zccbooks;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/nyikas/{nyikaId}")
public class NyikaHouseholdCandidateController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;

    public NyikaHouseholdCandidateController(JdbcTemplate jdbc, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    @GetMapping("/household-candidates")
    public List<CandidateDto> candidates(@PathVariable UUID nyikaId, @RequestParam UUID cellId) {
        Unit nyika = jdbc.query("""
            SELECT id, organisation_id, level FROM organisational_units
            WHERE id = ? AND active = TRUE
            """, (rs, n) -> new Unit(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getString("level")), nyikaId)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Active Nyika not found."));
        if (!"NYIKA".equals(nyika.level())) throw new IllegalArgumentException("The selected unit is not a Nyika.");
        authorization.requireUnitPermission("MEMBERS_VIEW", nyikaId);
        Integer cellCount = jdbc.queryForObject("""
            WITH RECURSIVE descendants AS (
                SELECT id FROM organisational_units WHERE id = ?
                UNION ALL
                SELECT child.id FROM organisational_units child
                JOIN descendants parent ON child.parent_id = parent.id
            )
            SELECT COUNT(*) FROM organisational_units
            WHERE id = ? AND id IN (SELECT id FROM descendants)
              AND organisation_id = ? AND level = 'CELL' AND active = TRUE
            """, Integer.class, nyikaId, cellId, nyika.organisationId());
        if (cellCount == null || cellCount == 0) throw new IllegalArgumentException("Select an active Cell within your Nyika.");
        return jdbc.query("""
            SELECT m.id, m.first_name, m.last_name, m.classification_id,
                   c.name AS classification_name, m.elite_number, m.zcc_app_number
            FROM members m
            LEFT JOIN classifications c ON c.id = m.classification_id
                AND c.organisation_id = m.organisation_id
            WHERE m.organisation_id = ? AND m.cell_id = ? AND m.branch_id IS NULL
              AND m.active = TRUE
              AND NOT EXISTS (
                  SELECT 1 FROM member_household_memberships membership
                  WHERE membership.member_id = m.id
                    AND membership.organisation_id = m.organisation_id
                    AND membership.valid_to IS NULL
              )
            ORDER BY m.last_name, m.first_name
            """, (rs, n) -> new CandidateDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("first_name"),
                rs.getString("last_name"), rs.getObject("classification_id") == null ? null :
                    rs.getObject("classification_id", UUID.class).toString(),
                rs.getString("classification_name"), rs.getString("elite_number"),
                rs.getString("zcc_app_number")
            ), nyika.organisationId(), cellId);
    }

    public record CandidateDto(String id, String firstName, String lastName, String classificationId,
            String classificationName, String eliteNumber, String zccAppNumber) {}
    private record Unit(UUID id, UUID organisationId, String level) {}
}
