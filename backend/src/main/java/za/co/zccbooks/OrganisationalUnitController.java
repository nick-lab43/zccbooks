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
public class OrganisationalUnitController {
    private static final List<String> LEVELS = List.of(
        "COUNTRY", "PROVINCE_STATE", "DISTRICT", "NYIKA",
        "TABHERA", "CELL", "TABHERA_YEMUMBA"
    );

    private final JdbcTemplate jdbc;

    public OrganisationalUnitController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/organisations/{organisationId}/units")
    public List<UnitDto> units(@PathVariable UUID organisationId) {
        requireOrganisation(organisationId);
        return jdbc.query("""
            SELECT id, organisation_id, parent_id, level, code, name, active
            FROM organisational_units
            WHERE organisation_id = ?
            ORDER BY level, name
            """, (rs, n) -> new UnitDto(
                rs.getObject("id", UUID.class).toString(),
                rs.getObject("organisation_id", UUID.class).toString(),
                rs.getObject("parent_id") == null ? null : rs.getObject("parent_id", UUID.class).toString(),
                rs.getString("level"), rs.getString("code"), rs.getString("name"), rs.getBoolean("active")
            ), organisationId);
    }

    @GetMapping("/units/{parentId}/children")
    public List<UnitDto> children(@PathVariable UUID parentId) {
        UnitRow parent = requireUnit(parentId);
        return jdbc.query("""
            SELECT id, organisation_id, parent_id, level, code, name, active
            FROM organisational_units
            WHERE organisation_id = ? AND parent_id = ?
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
        return createUnit(organisationId, null, "COUNTRY", request);
    }

    @PostMapping("/units/{parentId}/children")
    public ResponseEntity<UnitDto> createChild(@PathVariable UUID parentId,
            @Valid @RequestBody CreateUnit request) {
        UnitRow parent = requireUnit(parentId);
        int parentIndex = LEVELS.indexOf(parent.level());
        if (parentIndex < 0 || parentIndex == LEVELS.size() - 1) {
            throw new IllegalArgumentException("Tabhera yemumba is the lowest organisational level and cannot contain child units.");
        }
        return createUnit(parent.organisationId(), parent.id(), LEVELS.get(parentIndex + 1), request);
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
            """, (rs, n) -> new UnitRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class),
                rs.getString("level")
            ), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Organisational unit not found."));
    }

    private void requireOrganisation(UUID id) {
        if (jdbc.queryForList("SELECT id FROM organisations WHERE id = ?", UUID.class, id).isEmpty()) {
            throw new ResourceNotFoundException("Organisation not found.");
        }
    }

    public record CreateUnit(@NotBlank @Size(max = 60) String code,
                             @NotBlank @Size(max = 160) String name) {}
    public record UnitDto(String id, String organisationId, String parentId,
                          String level, String code, String name, boolean active) {}
    private record UnitRow(UUID id, UUID organisationId, String level) {}
}
