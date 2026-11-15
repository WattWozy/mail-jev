package dev.jefe.mailsorter.infra.mail;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class GreenMailFixtureTest {

    @RegisterExtension
    static final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("me@example.com", "secret"));

    @Test
    void aDeliveredMessageIsStoredInTheInbox() throws Exception {
        GreenMailUser user = greenMail.setUser("me@example.com", "secret");
        MimeMessage message = GreenMailUtil.createTextEmail(
                "me@example.com", "news@shop.com", "Weekly deals", "Big discounts inside",
                greenMail.getImap().getServerSetup());
        message.addHeader("List-Unsubscribe", "<mailto:unsubscribe@shop.com>");

        user.deliver(message);

        assertThat(greenMail.getReceivedMessages()).hasSize(1);
        assertThat(greenMail.getReceivedMessages()[0].getSubject()).isEqualTo("Weekly deals");
    }
}