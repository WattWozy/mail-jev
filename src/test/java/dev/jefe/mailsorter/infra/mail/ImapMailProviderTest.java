package dev.jefe.mailsorter.infra.mail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import dev.jefe.mailsorter.application.exception.MailAccessException;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.application.port.out.dto.MailOperation;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Email;
import dev.jefe.mailsorter.domain.config.MailboxConfig;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;


class ImapMailProviderTest {

    @RegisterExtension
    static final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP);

    private GreenMailUser user;
    private MailProvider provider;

    private Store rawStore() throws MessagingException {
        Store store = Session.getInstance(new Properties()).getStore("imap");
        store.connect("127.0.0.1", greenMail.getImap().getPort(), "me@example.com", "secret");
        return store;
    }

    private List<String> subjectsIn(String folderName) throws MessagingException {
        Store store = rawStore();
        try {
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) return List.of();
            folder.open(Folder.READ_ONLY);
            List<String> subjects = new ArrayList<>();
            for (Message m : folder.getMessages()) subjects.add(m.getSubject());
            folder.close(false);
            return subjects;
        } finally {
            store.close();
        }
    }

    private Set<String> userFlagsIn(String folderName, String subject) throws MessagingException {
        Store store = rawStore();
        try {
            Folder folder = store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);
            for (Message m : folder.getMessages()) {
                if (subject.equals(m.getSubject())) {
                    Set<String> flags = Set.of(m.getFlags().getUserFlags());
                    folder.close(false);
                    return flags;
                }
            }
            folder.close(false);
            return Set.of();
        } finally {
            store.close();
        }
    }

    @BeforeEach
    void connect() {
        user = greenMail.setUser("me@example.com", "secret");
        provider = new ImapMailProviderFactory().connect(new MailboxConfig(
                "imap", "127.0.0.1", greenMail.getImap().getPort(), "me@example.com", "secret",
                Map.of("ssl", "false")));
    }

    /** A null messageId delivers a message with no Message-ID header at all. */
    private void deliver(String messageId, String subject, String body, Map<String, String> headers)
            throws Exception {
        var session = GreenMailUtil.getSession(greenMail.getImap().getServerSetup());
        var message = new MimeMessage(session) {
            @Override protected void updateMessageID() throws MessagingException {
                if (messageId != null) setHeader("Message-ID", messageId);
            }
        };
        message.setFrom(new InternetAddress("news@shop.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("me@example.com"));
        message.setSubject(subject);
        message.setText(body);
        for (var h : headers.entrySet()) message.addHeader(h.getKey(), h.getValue());
        user.deliver(message);
    }

//    @Test
//    void listsDeliveredMessagesUsingMessageIdAsIdAndNormalizedHeaders() throws Exception {
//        deliver("<m1@test>", "Weekly deals", "Big discounts inside", Map.of("List-Unsubscribe", "<mailto:u@shop.com>"));
//        deliver("<m2@test>", "Lunch?", "Are you free tomorrow", Map.of());
//
//        var emails = provider.listUnprocessed(10);
//
//        assertThat(emails).extracting(Email::id).containsExactly("<m1@test>", "<m2@test>");
//        assertThat(emails.get(0).from()).isEqualTo("news@shop.com");
//        assertThat(emails.get(0).subject()).isEqualTo("Weekly deals");
//        assertThat(emails.get(0).snippet()).isEqualTo("Big discounts inside");
//        assertThat(emails.get(0).header("list-unsubscribe")).contains("<mailto:u@shop.com>");
//    }

    @Test
    void theAccountIdIsTheSameKeyTheSavedAccountsUse() {
        assertThat(provider.accountId()).isEqualTo("imap:127.0.0.1|me@example.com");
    }

    @Test
    void respectsTheLimit() throws Exception {
        deliver("<m1@test>", "a", "x", Map.of());
        deliver("<m2@test>", "b", "x", Map.of());
        deliver("<m3@test>", "c", "x", Map.of());

        assertThat(provider.listUnprocessed(2)).hasSize(2);
    }

    @Test
    void aMessageWithoutMessageIdGetsAStableId() throws Exception {
        deliver(null, "No id", "body", Map.of());

        var first = provider.listUnprocessed(10).get(0).id();
        var second = provider.listUnprocessed(10).get(0).id();

        assertThat(first).isNotBlank().isEqualTo(second);
    }

    @Test
    void markedMessagesAreNoLongerListed() throws Exception {
        deliver("<m1@test>", "one", "x", Map.of());
        deliver("<m2@test>", "two", "x", Map.of());

        provider.markProcessed("<m1@test>");

        assertThat(provider.listUnprocessed(10)).extracting(Email::id).containsExactly("<m2@test>");
    }

    @Test
    void actingOnAnUnknownMessageFails() {
        assertThatThrownBy(() -> provider.archive("<nope@test>")).isInstanceOf(MailAccessException.class);
        assertThatThrownBy(() -> provider.markProcessed("<nope@test>")).isInstanceOf(MailAccessException.class);
    }

    @Test
    void archiveMovesTheMessageToAnArchiveFolderCreatedOnDemand() throws Exception {
        deliver("<m1@test>", "one", "x", Map.of());

        provider.archive("<m1@test>");

        assertThat(subjectsIn("INBOX")).isEmpty();
        assertThat(subjectsIn("Archive")).containsExactly("one");
    }

    @Test
    void moveToTrashMovesTheMessageOutOfTheInbox() throws Exception {
        deliver("<m1@test>", "one", "x", Map.of());

        provider.moveToTrash("<m1@test>");

        assertThat(subjectsIn("INBOX")).isEmpty();
        assertThat(subjectsIn("Trash")).containsExactly("one");
    }

    @Test
    void aMovedMessageCanStillBeMarkedProcessed() throws Exception {
        deliver("<m1@test>", "one", "x", Map.of());
        provider.archive("<m1@test>");

        assertThatCode(() -> provider.markProcessed("<m1@test>")).doesNotThrowAnyException();
    }

    @Test
    void aMessageWithoutMessageIdCanStillBeArchived() throws Exception {
        deliver(null, "no id", "x", Map.of());
        String id = provider.listUnprocessed(10).get(0).id();

        provider.archive(id);

        assertThat(subjectsIn("INBOX")).isEmpty();
        assertThat(subjectsIn("Archive")).containsExactly("no id");
    }

    @Test
    void restoreBringsAnArchivedMessageBackToTheInbox() throws Exception {
        deliver("<m1@test>", "one", "x", Map.of());
        provider.archive("<m1@test>");

        provider.restoreToInbox("<m1@test>");

        assertThat(subjectsIn("INBOX")).containsExactly("one");
        assertThat(subjectsIn("Archive")).isEmpty();
    }

    @Test
    void restoreAlsoWorksFromTrash() throws Exception {
        deliver("<m1@test>", "one", "x", Map.of());
        provider.moveToTrash("<m1@test>");

        provider.restoreToInbox("<m1@test>");

        assertThat(subjectsIn("INBOX")).containsExactly("one");
        assertThat(subjectsIn("Trash")).isEmpty();
    }

    @Test
    void aRestoredMessageKeepsItsProcessedFlagSoItIsNotSortedAgain() throws Exception {
        deliver("<m1@test>", "one", "x", Map.of());
        provider.archive("<m1@test>");
        provider.markProcessed("<m1@test>");

        provider.restoreToInbox("<m1@test>");

        assertThat(subjectsIn("INBOX")).containsExactly("one");
        assertThat(provider.listUnprocessed(10)).isEmpty();
    }

    @Test
    void labelsRoundTrip() throws Exception {
        deliver("<m1@test>", "one", "x", Map.of());

        provider.addLabel("<m1@test>", "mail-sorter/review");
        assertThat(userFlagsIn("INBOX", "one")).contains("mail-sorter/review");

        provider.removeLabel("<m1@test>", "mail-sorter/review");
        assertThat(userFlagsIn("INBOX", "one")).doesNotContain("mail-sorter/review");
    }

    // -- user folders -------------------------------------------------------------

    @Test
    void applyAllFilesMessagesIntoUserFoldersCreatedOnDemand() throws Exception {
        deliver("<m1@test>", "sofa", "x", Map.of());
        deliver("<m2@test>", "flight deal", "x", Map.of());
        deliver("<m3@test>", "lamp", "x", Map.of());

        var failed = provider.applyAll(List.of(
                new MailOperation("<m1@test>", Action.MOVE_TO_FOLDER, "Ikea"),
                new MailOperation("<m2@test>", Action.MOVE_TO_FOLDER, "Travel promotions"),
                new MailOperation("<m3@test>", Action.MOVE_TO_FOLDER, "Ikea")), "mail-sorter/review");

        assertThat(failed).isEmpty();
        assertThat(subjectsIn("INBOX")).isEmpty();
        assertThat(subjectsIn("Ikea")).containsExactlyInAnyOrder("sofa", "lamp");
        assertThat(subjectsIn("Travel promotions")).containsExactly("flight deal");
    }

    @Test
    void aFiledMessageKeepsItsProcessedMarker() throws Exception {
        deliver("<m1@test>", "sofa", "x", Map.of());

        provider.applyAll(List.of(new MailOperation("<m1@test>", Action.MOVE_TO_FOLDER, "Ikea")), "mail-sorter/review");

        assertThat(userFlagsIn("Ikea", "sofa")).contains("mail-sorter-processed");
    }

    @Test
    void foldersAndOtherActionsMixInOneBatch() throws Exception {
        deliver("<m1@test>", "sofa", "x", Map.of());
        deliver("<m2@test>", "news", "x", Map.of());

        provider.applyAll(List.of(
                new MailOperation("<m1@test>", Action.MOVE_TO_FOLDER, "Ikea"),
                new MailOperation("<m2@test>", Action.ARCHIVE)), "mail-sorter/review");

        assertThat(subjectsIn("Ikea")).containsExactly("sofa");
        assertThat(subjectsIn("Archive")).containsExactly("news");
    }

    @Test
    void aFiledMessageCanStillBeReadAndRestored() throws Exception {
        deliver("<m1@test>", "sofa", "x", Map.of());
        provider.applyAll(List.of(new MailOperation("<m1@test>", Action.MOVE_TO_FOLDER, "Ikea")), "mail-sorter/review");

        assertThat(provider.read("<m1@test>").subject()).isEqualTo("sofa");

        provider.restoreToInbox("<m1@test>");
        assertThat(subjectsIn("INBOX")).containsExactly("sofa");
        assertThat(subjectsIn("Ikea")).isEmpty();
    }

    // -- failure reasons ------------------------------------------------------------

    @Test
    void aMessageThatIsNoLongerInTheInboxFailsWithAReason() throws Exception {
        deliver("<m1@test>", "one", "x", Map.of());

        var failed = provider.applyAll(List.of(
                new MailOperation("<m1@test>", Action.ARCHIVE),
                new MailOperation("<gone@test>", Action.ARCHIVE)), "mail-sorter/review");

        assertThat(failed).containsOnlyKeys("<gone@test>");
        assertThat(failed.get("<gone@test>")).contains("not found in INBOX");
        assertThat(subjectsIn("Archive")).containsExactly("one");
    }

    // -- servers whose header search finds nothing (seen on GMX) ---------------------

    /** Behaves like GMX: SEARCH HEADER Message-ID returns no hits. */
    private ImapMailProvider withoutHeaderSearch() throws MessagingException {
        return new ImapMailProvider(rawStore(), "Archive", "Trash", "imap:gmx-like|me@example.com", false);
    }

    @Test
    void listedMessagesAreFoundByUidWhenTheServersHeaderSearchFindsNothing() throws Exception {
        deliver("<m1@test>", "news", "x", Map.of());
        deliver("<m2@test>", "sofa", "x", Map.of());
        deliver(null, "no id", "x", Map.of());
        var gmxLike = withoutHeaderSearch();
        String noId = gmxLike.listUnprocessed(10).stream()
                .filter(e -> e.subject().equals("no id")).findFirst().orElseThrow().id();

        var failed = gmxLike.applyAll(List.of(
                new MailOperation("<m1@test>", Action.ARCHIVE),
                new MailOperation("<m2@test>", Action.MOVE_TO_FOLDER, "Ikea"),
                new MailOperation(noId, Action.MOVE_TO_TRASH)), "mail-sorter/review");

        assertThat(failed).isEmpty();
        assertThat(subjectsIn("INBOX")).isEmpty();
        assertThat(subjectsIn("Archive")).containsExactly("news");
        assertThat(subjectsIn("Ikea")).containsExactly("sofa");
        assertThat(subjectsIn("Trash")).containsExactly("no id");
    }

    @Test
    void aMessageThatWasNeverListedFallsBackToTheHeaderSearch() throws Exception {
        deliver("<m1@test>", "news", "x", Map.of());

        var failed = provider.applyAll(List.of(new MailOperation("<m1@test>", Action.ARCHIVE)), "mail-sorter/review");

        assertThat(failed).isEmpty();
        assertThat(subjectsIn("Archive")).containsExactly("news");
    }

    @Test
    void aListedMessageThatWasMovedElsewhereMeanwhileIsReportedNotFound() throws Exception {
        deliver("<m1@test>", "news", "x", Map.of());
        var gmxLike = withoutHeaderSearch();
        gmxLike.listUnprocessed(10);
        provider.archive("<m1@test>");   // moved by someone else (another client, a filter, ...)

        var failed = gmxLike.applyAll(List.of(new MailOperation("<m1@test>", Action.MOVE_TO_TRASH)), "mail-sorter/review");

        assertThat(failed).containsOnlyKeys("<m1@test>");
        assertThat(subjectsIn("Archive")).containsExactly("news");
    }

    // -- after a restart -------------------------------------------------------------

    private MailProvider reconnect() {
        return new ImapMailProviderFactory().connect(new MailboxConfig(
                "imap", "127.0.0.1", greenMail.getImap().getPort(), "me@example.com", "secret",
                Map.of("ssl", "false")));
    }

    @Test
    void afterARestartMailInAUserFolderCanStillBeReadAndRestored() throws Exception {
        deliver("<m1@test>", "sofa", "x", Map.of());
        provider.applyAll(List.of(new MailOperation("<m1@test>", Action.MOVE_TO_FOLDER, "Ikea")), "mail-sorter/review");

        var restarted = reconnect();   // a new session knows nothing about "Ikea"

        assertThat(restarted.read("<m1@test>").subject()).isEqualTo("sofa");
        restarted.restoreToInbox("<m1@test>");
        assertThat(subjectsIn("INBOX")).containsExactly("sofa");
        assertThat(subjectsIn("Ikea")).isEmpty();
    }
}
