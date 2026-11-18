package dev.jefe.mailsorter;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.net.URI;
import java.util.Map;

public final class DevMain {

    public static void main(String[] args) throws Exception {
        var greenMail = new GreenMail(new ServerSetup[] {
                new ServerSetup(3143, "127.0.0.1", ServerSetup.PROTOCOL_IMAP),
                new ServerSetup(3025, "127.0.0.1", ServerSetup.PROTOCOL_SMTP)});        greenMail.start();
        GreenMailUser user = greenMail.setUser("me@example.com", "secret");
        seed(greenMail, user);

        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String apiKey   = dotenv.get("JEV_API_KEY");
        String endpoint = dotenv.get("JEV_ENDPOINT");

        if (apiKey == null || endpoint == null) {
            System.err.println("Set JEV_API_KEY and JEV_ENDPOINT in .env");
            System.exit(1);
        }

        var web = CompositionRoot.build(
                new AppConfig(
                        "127.0.0.1",
                        8080,
                        URI.create(endpoint),
                        apiKey
                )
        );
        web.start();

        System.out.println("Dev mode → GreenMail IMAP + Real JEV");
        System.out.println("UI:    http://127.0.0.1:8080");
        System.out.println("IMAP:  127.0.0.1:3143  user me@example.com  password secret  (no SSL)");
    }

    private static void seed(GreenMail greenMail, GreenMailUser user) throws Exception {
        var session = GreenMailUtil.getSession(greenMail.getImap().getServerSetup());
        int[] n = {0};
        // [expected outcome] in comments
        mail(session, user, n, "news@javaweekly.com", "Weekly digest: 10 Java tips", Map.of("List-Unsubscribe", "<mailto:u@javaweekly.com>")); // rule -> ARCHIVE
        mail(session, user, n, "billing@hosting.com", "Your invoice #1042", Map.of("List-Unsubscribe", "<mailto:u@hosting.com>"));          // rule -> KEEP
        mail(session, user, n, "offers@shop.com", "Summer SALE: 40% off everything", Map.of());                                              // jev -> TRASH
        mail(session, user, n, "ana@example.com", "Lunch tomorrow?", Map.of());                                                              // jev -> KEEP
        mail(session, user, n, "notifications@linkedin.com", "Marta commented on your post", Map.of());                                      // jev -> ARCHIVE
        mail(session, user, n, "promo@weird-site.biz", "You are a WINNER - claim your prize", Map.of());                                     // jev -> TRASH
        mail(session, user, n, "carlos@client.com", "Quick question about the contract", Map.of());                                          // jev -> KEEP
        mail(session, user, n, "hello@gadgets.io", "Maybe interesting: new gadget", Map.of());                                               // jev 0.55 -> REVIEW
    }

    private static void mail(Session session, GreenMailUser user, int[] n, String from, String subject,
                             Map<String, String> headers) throws Exception {
        String id = "<seed-" + (++n[0]) + "@dev>";
        var message = new MimeMessage(session) {
            @Override protected void updateMessageID() throws MessagingException { setHeader("Message-ID", id); }
        };
        message.setFrom(new InternetAddress(from));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("me@example.com"));
        message.setSubject(subject);
        message.setText("Sample body for: " + subject);
        for (var h : headers.entrySet()) message.addHeader(h.getKey(), h.getValue());
        user.deliver(message);
    }
}