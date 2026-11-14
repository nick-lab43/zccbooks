package za.co.zccbooks;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

@Service
@ConditionalOnProperty(prefix = "app.mail", name = "enabled", havingValue = "true")
public class NyikaInvitationMailService {
    private final RestClient resend;
    private final String from;
    private final String frontendUrl;

    public NyikaInvitationMailService(RestClient.Builder restClientBuilder,
            @Value("${RESEND_API_KEY:}") String apiKey,
            @Value("${app.mail.from}") String from,
            @Value("${app.frontend-url}") String frontendUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("RESEND_API_KEY must be configured when invitation email is enabled.");
        }
        this.resend = restClientBuilder.baseUrl("https://api.resend.com")
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
        this.from = from;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    public String activationUrl(String token) {
        return frontendUrl + "/accept-invitation?token="
            + UriUtils.encodeQueryParam(token, StandardCharsets.UTF_8);
    }

    public void sendInvitation(String email, String displayName, String username, String nyikaName, String url) {
        String subject = "ZCC Books — Nyika administrator invitation";
        String text = "Hello " + displayName + ",\n\nYou have been invited to administer " + nyikaName
            + " in ZCC Books.\n\nYour assigned username is: " + username
            + "\n\nUse the secure link below to create your password. The link expires in 48 hours and can only be used once.\n\n"
            + url + "\n\nIf you were not expecting this invitation, ignore this email.";
        String html = "<p>Hello " + escapeHtml(displayName) + ",</p>"
            + "<p>You have been invited to administer <strong>" + escapeHtml(nyikaName)
            + "</strong> in ZCC Books.</p><p>Your assigned username is: <strong>"
            + escapeHtml(username) + "</strong></p><p>Use the secure link below to create your password. "
            + "The link expires in 48 hours and can only be used once.</p><p><a href=\""
            + escapeHtml(url) + "\">Activate administrator account</a></p>"
            + "<p>If you were not expecting this invitation, ignore this email.</p>";

        try {
            resend.post().uri("/emails")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("from", from, "to", new String[]{email}, "subject", subject,
                    "text", text, "html", html))
                .retrieve().toBodilessEntity();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invitation email could not be delivered. Check the Resend API key, verified sender and Resend email logs.", exception);
        }
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
