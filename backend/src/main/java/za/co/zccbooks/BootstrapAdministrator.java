package za.co.zccbooks;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapAdministrator implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final String displayName;

    public BootstrapAdministrator(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap-admin.username:}") String username,
            @Value("${app.bootstrap-admin.password:}") String password,
            @Value("${app.bootstrap-admin.display-name:Central Administrator}") String displayName) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username.isBlank() && password.isBlank()) return;
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Both BOOTSTRAP_ADMIN_USERNAME and BOOTSTRAP_ADMIN_PASSWORD must be set together.");
        }
        if (password.length() < 12) throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must contain at least 12 characters.");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_users", Integer.class);
        if (count != null && count > 0) return;

        UUID userId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO admin_users (id, username, display_name, password_hash)
            VALUES (?, ?, ?, ?)
            """, userId, username.trim(), displayName.trim(), passwordEncoder.encode(password));
        jdbc.update("""
            INSERT INTO admin_role_assignments (id, user_id, role_id, scope_type)
            VALUES (?, ?, '10000000-0000-0000-0000-000000000001', 'GLOBAL')
            """, UUID.randomUUID(), userId);
        jdbc.update("""
            INSERT INTO admin_audit_events (id, actor_user_id, action_code, target_type, target_id, details)
            VALUES (?, ?, 'BOOTSTRAP_ADMIN_CREATED', 'ADMIN_USER', ?, '{"source":"environment-bootstrap"}'::JSONB)
            """, UUID.randomUUID(), userId, userId);
    }
}
