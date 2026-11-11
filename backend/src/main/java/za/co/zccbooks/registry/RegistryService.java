package za.co.zccbooks.registry;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static za.co.zccbooks.registry.RegistryDtos.*;

@Service
public class RegistryService {
    private final JdbcTemplate jdbc;

    public RegistryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OrganisationResponse> organisations() {
        return jdbc.query("""
            SELECT id, name, created_at FROM organisation ORDER BY name
            """, (rs, row) -> new OrganisationResponse(
                rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("created_at", OffsetDateTime.class)));
    }

    @Transactional
    public OrganisationResponse createOrganisation(OrganisationRequest request) {
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();
        jdbc.update("INSERT INTO organisation (id, name, created_at) VALUES (?, ?, ?)",
            id, request.name().trim(), createdAt);
        return new OrganisationResponse(id, request.name().trim(), createdAt);
    }

    public List<BranchResponse> branches(UUID organisationId) {
        requireOrganisation(organisationId);
        return jdbc.query("""
            SELECT id, organisation_id, code, name, district_name, country_code
            FROM branch WHERE organisation_id = ? ORDER BY name
            """, this::branchRow, organisationId);
    }

    @Transactional
    public BranchResponse createBranch(UUID organisationId, BranchRequest request) {
        requireOrganisation(organisationId);
        UUID id = UUID.randomUUID();
        String code = request.code().trim().toUpperCase();
        try {
            jdbc.update("""
                INSERT INTO branch (id, organisation_id, code, name, district_name, country_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, organisationId, code, request.name().trim(),
                blankToNull(request.districtName()), blankToNull(request.countryCode()));
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A branch with this code already exists in the organisation.");
        }
        return new BranchResponse(id, organisationId, code, request.name().trim(),
            blankToNull(request.districtName()), blankToNull(request.countryCode()));
    }

    public List<ClassificationResponse> classifications(UUID organisationId) {
        requireOrganisation(organisationId);
        return jdbc.query("""
            SELECT id, organisation_id, code, name, active
            FROM member_classification WHERE organisation_id = ? ORDER BY name
            """, (rs, row) -> new ClassificationResponse(
                rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getBoolean("active")),
            organisationId);
    }

    @Transactional
    public ClassificationResponse createClassification(UUID organisationId,
                                                         ClassificationRequest request) {
        requireOrganisation(organisationId);
        UUID id = UUID.randomUUID();
        String code = request.code().trim().toUpperCase();
        try {
            jdbc.update("""
                INSERT INTO member_classification (id, organisation_id, code, name, active)
                VALUES (?, ?, ?, ?, TRUE)
                """, id, organisationId, code, request.name().trim());
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A classification with this code already exists.");
        }
        return new ClassificationResponse(id, organisationId, code, request.name().trim(), true);
    }

    public List<MemberResponse> members(UUID branchId, String search) {
        requireBranch(branchId);
        String term = search == null ? "" : search.trim().toLowerCase();
        return jdbc.query("""
            SELECT m.id, m.branch_id, m.zcc_app_number, m.first_name, m.last_name,
                   m.classification_id, c.name AS classification_name, m.active, m.created_at
            FROM member m
            LEFT JOIN member_classification c ON c.id = m.classification_id
            WHERE m.branch_id = ?
              AND (? = '' OR LOWER(m.first_name) LIKE '%' || ? || '%'
                   OR LOWER(m.last_name) LIKE '%' || ? || '%'
                   OR LOWER(COALESCE(m.zcc_app_number, '')) LIKE '%' || ? || '%')
            ORDER BY m.last_name, m.first_name
            """, this::memberRow, branchId, term, term, term, term);
    }

    @Transactional
    public MemberResponse createMember(UUID branchId, MemberRequest request) {
        UUID organisationId = requireBranch(branchId);
        if (request.classificationId() != null) {
            Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM member_classification
                WHERE id = ? AND organisation_id = ? AND active = TRUE
                """, Integer.class, request.classificationId(), organisationId);
            if (count == null || count == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The selected classification is not active in this organisation.");
            }
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();
        String appNumber = blankToNull(request.zccAppNumber());
        try {
            jdbc.update("""
                INSERT INTO member (id, branch_id, zcc_app_number, first_name, last_name,
                                    classification_id, active, created_at)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, ?)
                """, id, branchId, appNumber, request.firstName().trim(),
                request.lastName().trim(), request.classificationId(), createdAt);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This ZCC app number is already registered.");
        }
        return memberById(id);
    }

    private MemberResponse memberById(UUID id) {
        return jdbc.queryForObject("""
            SELECT m.id, m.branch_id, m.zcc_app_number, m.first_name, m.last_name,
                   m.classification_id, c.name AS classification_name, m.active, m.created_at
            FROM member m LEFT JOIN member_classification c ON c.id = m.classification_id
            WHERE m.id = ?
            """, this::memberRow, id);
    }

    private void requireOrganisation(UUID id) {
        if (!exists("SELECT COUNT(*) FROM organisation WHERE id = ?", id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Organisation not found.");
        }
    }

    private UUID requireBranch(UUID id) {
        List<UUID> organisationIds = jdbc.query(
            "SELECT organisation_id FROM branch WHERE id = ?",
            (rs, row) -> rs.getObject("organisation_id", UUID.class), id);
        if (organisationIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found.");
        }
        return organisationIds.get(0);
    }

    private boolean exists(String sql, UUID id) {
        Integer count = jdbc.queryForObject(sql, Integer.class, id);
        return count != null && count > 0;
    }

    private BranchResponse branchRow(ResultSet rs, int row) throws SQLException {
        return new BranchResponse(rs.getObject("id", UUID.class),
            rs.getObject("organisation_id", UUID.class), rs.getString("code"),
            rs.getString("name"), rs.getString("district_name"), rs.getString("country_code"));
    }

    private MemberResponse memberRow(ResultSet rs, int row) throws SQLException {
        return new MemberResponse(rs.getObject("id", UUID.class),
            rs.getObject("branch_id", UUID.class), rs.getString("zcc_app_number"),
            rs.getString("first_name"), rs.getString("last_name"),
            rs.getObject("classification_id", UUID.class), rs.getString("classification_name"),
            rs.getBoolean("active"), rs.getObject("created_at", OffsetDateTime.class));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}