package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/members")
public class MemberProfileController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public MemberProfileController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/{memberId}/profile")
    public MemberProfile profile(@PathVariable UUID memberId) {
        MemberRow member = requireMember(memberId);
        authorization.requireUnitPermission("MEMBERS_VIEW", member.cellId());
        return jdbc.queryForObject("""
            SELECT first_name, last_name, zcc_app_number, phone_number, email, date_of_birth
            FROM members WHERE id = ?
            """, (rs, n) -> new MemberProfile(
                memberId.toString(), rs.getString("first_name"), rs.getString("last_name"),
                rs.getString("zcc_app_number"), rs.getString("phone_number"), rs.getString("email"),
                rs.getDate("date_of_birth") == null ? null : rs.getDate("date_of_birth").toLocalDate()
            ), memberId);
    }

    @PatchMapping("/{memberId}/profile")
    @Transactional
    public MemberProfile updateProfile(@PathVariable UUID memberId,
            @Valid @RequestBody UpdateMemberProfile request) {
        MemberRow member = requireMember(memberId);
        authorization.requireUnitPermission("MEMBERS_MANAGE", member.cellId());
        String firstName = request.firstName().trim();
        String lastName = request.lastName().trim();
        String phone = blankToNull(request.phoneNumber());
        String email = blankToNull(request.email());
        if (request.dateOfBirth() != null && request.dateOfBirth().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Date of birth cannot be in the future.");
        }
        jdbc.update("""
            UPDATE members
            SET first_name = ?, last_name = ?, phone_number = ?, email = ?, date_of_birth = ?
            WHERE id = ? AND organisation_id = ? AND cell_id = ?
            """, firstName, lastName, phone, email,
            request.dateOfBirth() == null ? null : Date.valueOf(request.dateOfBirth()),
            memberId, member.organisationId(), member.cellId());
        audit.record("MEMBER_PROFILE_UPDATED", "MEMBER", memberId,
            Map.of("organisationId", member.organisationId().toString(),
                "cellId", member.cellId().toString()));
        return profile(memberId);
    }

    private MemberRow requireMember(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, cell_id FROM members WHERE id = ? AND cell_id IS NOT NULL
            """, (rs, n) -> new MemberRow(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getObject("cell_id", UUID.class)), id)
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Cell member not found."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record MemberRow(UUID id, UUID organisationId, UUID cellId) {}
    public record MemberProfile(String id, String firstName, String lastName, String zccAppNumber,
            String phoneNumber, String email, LocalDate dateOfBirth) {}
    public record UpdateMemberProfile(@NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @Size(max = 30) String phoneNumber,
            @Email @Size(max = 254) String email,
            LocalDate dateOfBirth) {}
}
