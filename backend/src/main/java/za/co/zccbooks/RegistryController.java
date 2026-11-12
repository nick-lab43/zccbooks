package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RegistryController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;

    public RegistryController(JdbcTemplate jdbc, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    private static final RowMapper<OrganisationDto> ORGANISATION_ROW = (rs, n) ->
        new OrganisationDto(rs.getObject("id", UUID.class).toString(), rs.getString("name"), rs.getString("created_at"));
    private static final RowMapper<BranchDto> BRANCH_ROW = (rs, n) ->
        new BranchDto(rs.getObject("id", UUID.class).toString(), rs.getObject("organisation_id", UUID.class).toString(),
            rs.getString("code"), rs.getString("name"), rs.getString("district_name"), rs.getString("country_code"));
    private static final RowMapper<ClassificationDto> CLASSIFICATION_ROW = (rs, n) ->
        new ClassificationDto(rs.getObject("id", UUID.class).toString(), rs.getString("code"),
            rs.getString("name"), rs.getBoolean("active"));
    private static final RowMapper<MemberDto> MEMBER_ROW = (rs, n) ->
        new MemberDto(rs.getObject("id", UUID.class).toString(), rs.getObject("branch_id", UUID.class).toString(),
            rs.getString("zcc_app_number"), rs.getString("first_name"), rs.getString("last_name"),
            rs.getObject("classification_id") == null ? null : rs.getObject("classification_id", UUID.class).toString(),
            rs.getString("classification_name"), rs.getBoolean("active"));

    @GetMapping("/health")
    public HealthDto health() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return new HealthDto("ok");
    }

    @GetMapping("/organisations")
    public List<OrganisationDto> organisations() {
        authorization.requireAnyOrganisationPermission("ORG_VIEW");
        return jdbc.query("SELECT id, name, created_at::text AS created_at FROM organisations ORDER BY name", ORGANISATION_ROW)
            .stream().filter(organisation -> authorization.canAccessOrganisation("ORG_VIEW", UUID.fromString(organisation.id())))
            .toList();
    }

    @PostMapping("/organisations")
    public ResponseEntity<OrganisationDto> createOrganisation(@Valid @RequestBody CreateOrganisation request) {
        authorization.requireGlobalPermission("ORG_MANAGE");
        UUID id = UUID.randomUUID();
        return ResponseEntity.status(HttpStatus.CREATED).body(jdbc.queryForObject(
            "INSERT INTO organisations (id, name) VALUES (?, ?) RETURNING id, name, created_at::text AS created_at",
            ORGANISATION_ROW, id, request.name().trim()));
    }

    @GetMapping("/organisations/{organisationId}/branches")
    public List<BranchDto> branches(@PathVariable UUID organisationId) {
        requireOrganisation(organisationId);
        authorization.requireOrganisationPermission("ORG_VIEW", organisationId);
        return jdbc.query("SELECT id, organisation_id, code, name, district_name, country_code FROM branches WHERE organisation_id = ? ORDER BY name",
            BRANCH_ROW, organisationId);
    }

    @PostMapping("/organisations/{organisationId}/branches")
    public ResponseEntity<BranchDto> createBranch(@PathVariable UUID organisationId, @Valid @RequestBody CreateBranch request) {
        requireOrganisation(organisationId);
        authorization.requireOrganisationPermission("ORG_MANAGE", organisationId);
        UUID id = UUID.randomUUID();
        return ResponseEntity.status(HttpStatus.CREATED).body(jdbc.queryForObject(
            "INSERT INTO branches (id, organisation_id, code, name, district_name, country_code) VALUES (?, ?, ?, ?, ?, ?) RETURNING id, organisation_id, code, name, district_name, country_code",
            BRANCH_ROW, id, organisationId, request.code().trim(), request.name().trim(),
            blankToNull(request.districtName()), blankToNull(request.countryCode())));
    }

    @GetMapping("/organisations/{organisationId}/classifications")
    public List<ClassificationDto> classifications(@PathVariable UUID organisationId) {
        requireOrganisation(organisationId);
        authorization.requireOrganisationPermission("MEMBERS_VIEW", organisationId);
        return jdbc.query("SELECT id, code, name, active FROM classifications WHERE organisation_id = ? ORDER BY name",
            (rs, n) -> new ClassificationDto(rs.getObject("id", UUID.class).toString(), rs.getString("code"),
                rs.getString("name"), rs.getBoolean("active")), organisationId);
    }

    @PostMapping("/organisations/{organisationId}/classifications")
    public ResponseEntity<ClassificationDto> createClassification(@PathVariable UUID organisationId,
            @Valid @RequestBody CreateClassification request) {
        requireOrganisation(organisationId);
        authorization.requireOrganisationPermission("MEMBERS_MANAGE", organisationId);
        UUID id = UUID.randomUUID();
        return ResponseEntity.status(HttpStatus.CREATED).body(jdbc.queryForObject(
            "INSERT INTO classifications (id, organisation_id, code, name) VALUES (?, ?, ?, ?) RETURNING id, code, name, active",
            CLASSIFICATION_ROW, id, organisationId, request.code().trim(), request.name().trim()));
    }

    @GetMapping("/branches/{branchId}/members")
    public List<MemberDto> members(@PathVariable UUID branchId, @RequestParam(required = false) String search) {
        UUID organisationId = requireBranch(branchId);
        authorization.requireOrganisationPermission("MEMBERS_VIEW", organisationId);
        String term = search == null ? "" : search.trim();
        String pattern = "%" + term + "%";
        return jdbc.query("""
            SELECT m.id, m.branch_id, m.zcc_app_number, m.first_name, m.last_name,
                   m.classification_id, c.name AS classification_name, m.active
            FROM members m
            LEFT JOIN classifications c ON c.id = m.classification_id
            WHERE m.branch_id = ?
              AND (? = '' OR m.first_name ILIKE ? OR m.last_name ILIKE ?
                   OR COALESCE(m.zcc_app_number, '') ILIKE ?)
            ORDER BY m.last_name, m.first_name
            """, MEMBER_ROW, branchId, term, pattern, pattern, pattern);
    }

    @PostMapping("/branches/{branchId}/members")
    public ResponseEntity<MemberDto> createMember(@PathVariable UUID branchId, @Valid @RequestBody CreateMember request) {
        UUID organisationId = requireBranch(branchId);
        authorization.requireOrganisationPermission("MEMBERS_MANAGE", organisationId);
        UUID classificationId = request.classificationId();
        if (classificationId != null) {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM classifications WHERE id = ? AND organisation_id = ? AND active = TRUE",
                Integer.class, classificationId, organisationId);
            if (count == null || count == 0) throw new IllegalArgumentException("Select an active classification belonging to this organisation.");
        }
        UUID id = UUID.randomUUID();
        return ResponseEntity.status(HttpStatus.CREATED).body(jdbc.queryForObject("""
            INSERT INTO members (id, branch_id, zcc_app_number, first_name, last_name, classification_id)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id, branch_id, zcc_app_number, first_name, last_name, classification_id,
                      NULL::VARCHAR AS classification_name, active
            """, MEMBER_ROW, id, branchId, blankToNull(request.zccAppNumber()),
            request.firstName().trim(), request.lastName().trim(), classificationId));
    }

    private void requireOrganisation(UUID id) {
        if (jdbc.queryForList("SELECT id FROM organisations WHERE id = ?", UUID.class, id).isEmpty())
            throw new ResourceNotFoundException("Organisation not found.");
    }

    private UUID requireBranch(UUID id) {
        return jdbc.query("SELECT organisation_id FROM branches WHERE id = ?",
            (rs, n) -> rs.getObject(1, UUID.class), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Branch not found."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreateOrganisation(@NotBlank @Size(max = 160) String name) {}
    public record CreateBranch(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 160) String name,
        @Size(max = 160) String districtName, @Pattern(regexp = "[A-Z]{2}") String countryCode) {}
    public record CreateClassification(@NotBlank @Size(max = 60) String code, @NotBlank @Size(max = 120) String name) {}
    public record CreateMember(@Size(max = 80) String zccAppNumber, @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName, UUID classificationId) {}
    public record OrganisationDto(String id, String name, String createdAt) {}
    public record BranchDto(String id, String organisationId, String code, String name, String districtName, String countryCode) {}
    public record ClassificationDto(String id, String code, String name, boolean active) {}
    public record MemberDto(String id, String branchId, String zccAppNumber, String firstName, String lastName,
        String classificationId, String classificationName, boolean active) {}
    public record HealthDto(String status) {}
}
