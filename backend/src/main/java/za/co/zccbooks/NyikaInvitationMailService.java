package za.co.zccbooks;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;

@Service
@ConditionalOnProperty(prefix = "app.mail", name = "enabled", havingValue = "true")
public class NyikaInvitationMailService {
    private final JavaMailSender sender;
    private final String from;
    private final String frontendUrl;

    public NyikaInvitationMailService(JavaMailSender sender,
            @Value("${app.mail.from}") String from,
            @Value("${app.frontend-url}") String frontendUrl) {
        this.sender = sender;
        this.from = from;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    public String activationUrl(String token) {
        return frontendUrl + "/accept-invitation?token=" + UriUtils.encodeQueryParam(token, StandardCharsets.UTF_8);
    }

    public void sendInvitation(String email, String displayName, String username, String nyikaName, String url) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("ZCC Books — Nyika administrator invitation");
        message.setText("Hello " + displayName + ",\n\nYou have been invited to administer " + nyikaName
            + " in ZCC Books.\n\nYour assigned username is: " + username
            + "\n\nUse the secure link below to create your password. The link expires in 48 hours and can only be used once.\n\n"
            + url + "\n\nIf you were not expecting this invitation, ignore this email.");
        sender.send(message);
    }
}
