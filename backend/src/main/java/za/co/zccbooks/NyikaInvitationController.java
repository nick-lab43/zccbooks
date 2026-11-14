package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class NyikaInvitationController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;
    private final PasswordEncoder passwordEncoder;
    private final ObjectProvider<NyikaInvitationMailService> mailService;

    public NyikaInvitationController(JdbcTemplate jdbc, AuthorizationService authorization,
            AdminAuditService audit, PasswordEncoder passwordEncoder,
            ObjectProvider<NyikaInvitationMailService> mailService) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
    }

    @GetMapping("/admin/nyika-invitations")
    public List<InvitationDto> invitations() {
        authorization.requireGlobalPermission("ADMIN_MANAGE");
        return jdbc.query("""
            SELECT i.id, u.username, u.display_name, u.email, ou.code AS nyika_code,
                   ou.name AS nyika_name, i.expires_at::text,
                   CASE WHEN i.accepted_at IS NOT NULL THEN 'ACCEPTED'
                        WHEN i.revoked_at IS NOT NULL THEN 'REVOKED'
                        WHEN i.expires_at <= CURRENT_TIMESTAMP THEN 'EXPIRED'
                        ELSE 'PENDING' END AS status
            FROM admin_invitations i
            JOIN admin_users u ON u.id = i.user_id
            JOIN admin_role_assignments a ON a.user_id = u.id AND a.revoked_at IS NULL
            JOIN admin_roles r ON r.id = a.role_id AND r.role_code = 'NYIKA_ADMIN'
            JOIN organisational_units ou ON ou.id = a.scope_unit_id
            ORDER BY i.created_at DESC LIMIT 100
            """, (rs, n) -> new InvitationDto(rs.getObject("id", UUID.class).toString(),
                rs.getString("username"), rs.getString("display_name"), rs.getString("email"),
                rs.getString("nyika_code"), rs.getString("nyika_name"), rs.getString("expires_at"),
                rs.getString("status")));
    }

    @PostMapping("/admin/nyika-invitations")
    @Transactional
    public ResponseEntity<CreatedInvitation> create(@Valid @RequestBody CreateInvitation request) {
        authorization.requireGlobalPermission("ADMIN_MANAGE");
        NyikaInvitationMailService mail = mailService.getIfAvailable();
        if (mail == null) throw new IllegalStateException("Invitation email is not configured. Enable invitation email and configure RESEND_API_KEY and MAIL_FROM.");

        Nyika nyika = jdbc.query("""
            SELECT id, organisation_id, code, name FROM organisational_units
            WHERE id = ? AND level = 'NYIKA' AND active = TRUE
            """, (rs, n) -> new Nyika(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getString("code"), rs.getString("name")),
            request.nyikaId()).stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Select an active Nyika."));

        String username = availableUsername(nyika.code(), nyika.name());
        UUID userId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO admin_users (id, username, display_name, email, password_hash, enabled)
            VALUES (?, ?, ?, ?, ?, FALSE)
            """, userId, username, request.displayName().trim(), request.email().trim().toLowerCase(),
            passwordEncoder.encode(randomSecret()));

        UUID roleId = jdbc.queryForObject("SELECT id FROM admin_roles WHERE role_code = 'NYIKA_ADMIN'", UUID.class);
        jdbc.update("""
            INSERT INTO admin_role_assignments (id, user_id, role_id, scope_type, organisation_id, scope_unit_id)
            VALUES (?, ?, ?, 'UNIT', ?, ?)
            """, UUID.randomUUID(), userId, roleId, nyika.organisationId(), nyika.id());

        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String tokenHash = sha256(token);
        UUID invitationId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO admin_invitations (id, user_id, token_hash, invited_by, expires_at)
            VALUES (?, ?, ?, (SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)),
                    CURRENT_TIMESTAMP + INTERVAL '48 hours')
            """, invitationId, userId, tokenHash, authorization.currentUsername());

        String activationUrl = mail.activationUrl(token);
        mail.sendInvitation(request.email().trim(), request.displayName().trim(), username, nyika.name(), activationUrl);
        audit.record("NYIKA_ADMIN_INVITED", "ADMIN_INVITATION", invitationId,
            Map.of("username", username, "nyikaId", nyika.id().toString(), "email", request.email().trim().toLowerCase()));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new CreatedInvitation(invitationId.toString(), username, "PENDING", "48 hours"));
    }

    @PostMapping("/invitations/accept")
    @Transactional
    public ResponseEntity<Void> accept(@Valid @RequestBody AcceptInvitation request) {
        String hash = sha256(request.token());
        List<UUID> invitationIds = jdbc.query("""
            UPDATE admin_invitations SET accepted_at = CURRENT_TIMESTAMP
            WHERE token_hash = ? AND accepted_at IS NULL AND revoked_at IS NULL
              AND expires_at > CURRENT_TIMESTAMP
            RETURNING user_id
            """, (rs, n) -> rs.getObject("user_id", UUID.class), hash);
        if (invitationIds.isEmpty()) throw new IllegalArgumentException("This invitation is invalid, expired or has already been used.");
        UUID userId = invitationIds.get(0);
        int updated = jdbc.update("""
            UPDATE admin_users SET password_hash = ?, enabled = TRUE
            WHERE id = ? AND enabled = FALSE
            """, passwordEncoder.encode(request.password()), userId);
        if (updated != 1) throw new IllegalArgumentException("This administrator account cannot be activated.");
        jdbc.update("""
            INSERT INTO admin_audit_events (id, actor_user_id, action_code, target_type, target_id, details)
            VALUES (?, ?, 'NYIKA_ADMIN_ACTIVATED', 'ADMIN_USER', ?, '{}'::JSONB)
            """, UUID.randomUUID(), userId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/nyika-invitations/{invitationId}/revoke")
    @Transactional
    public ResponseEntity<Void> revoke(@PathVariable UUID invitationId) {
        authorization.requireGlobalPermission("ADMIN_MANAGE");
        int updated = jdbc.update("""
            UPDATE admin_invitations SET revoked_at = CURRENT_TIMESTAMP
            WHERE id = ? AND accepted_at IS NULL AND revoked_at IS NULL
            """, invitationId);
        if (updated == 0) throw new ResourceNotFoundException("Pending invitation not found.");
        UUID userId = jdbc.queryForObject("SELECT user_id FROM admin_invitations WHERE id = ?", UUID.class, invitationId);
        jdbc.update("UPDATE admin_users SET enabled = FALSE WHERE id = ?", userId);
        audit.record("NYIKA_ADMIN_INVITATION_REVOKED", "ADMIN_INVITATION", invitationId, Map.of());
        return ResponseEntity.noContent().build();
    }

    private String availableUsername(String code, String name) {
        String base = (code == null || code.isBlank() ? name : code).toLowerCase()
            .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isBlank()) base = "nyika";
        base = base.length() > 65 ? base.substring(0, 65) : base;
        String candidate = base + "_admin";
        int suffix = 2;
        while (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM admin_users WHERE LOWER(username) = LOWER(?))",
                Boolean.class, candidate))) {
            candidate = base + "_admin" + suffix++;
        }
        return candidate;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash invitation token.", exception);
        }
    }

    public record CreateInvitation(@NotNull UUID nyikaId, @NotBlank @Size(max = 160) String displayName,
                                   @NotBlank @Email @Size(max = 254) String email) {}
    public record AcceptInvitation(@NotBlank String token, @NotBlank @Size(min = 12, max = 100) String password) {}
    public record CreatedInvitation(String id, String username, String status, String expiresIn) {}
    public record InvitationDto(String id, String username, String displayName, String email,
                                String nyikaCode, String nyikaName, String expiresAt, String status) {}
    private record Nyika(UUID id, UUID organisationId, String code, String name) {}
}
