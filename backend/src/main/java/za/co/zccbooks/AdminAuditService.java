package za.co.zccbooks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AdminAuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void record(String action, String targetType, UUID targetId, Map<String, ?> details) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return;
        UUID actorId = jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authentication.getName());
        try {
            jdbc.update("""
                INSERT INTO admin_audit_events (id, actor_user_id, action_code, target_type, target_id, details)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), actorId, action, targetType, targetId, objectMapper.writeValueAsString(details));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialise administrator audit details.", exception);
        }
    }
}
