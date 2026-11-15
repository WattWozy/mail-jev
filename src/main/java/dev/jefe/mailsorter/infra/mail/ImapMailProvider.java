package dev.jefe.mailsorter.infra.mail;

import dev.jefe.mailsorter.application.exception.MailAccessException;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.domain.Email;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.HeaderTerm;
import jakarta.mail.search.SearchTerm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public final class ImapMailProvider implements MailProvider {

    static final String PROCESSED_FLAG = "mail-sorter-processed";
    private static final String INBOX = "INBOX";
    private static final String FALLBACK_PREFIX = "fallback:";
    private static final int SNIPPET_CHARS = 200;

    private final Store store;
    private final String archiveFolder;
    private final String trashFolder;

    ImapMailProvider(Store store) { this(store, "Archive", "Trash"); }

    ImapMailProvider(Store store, String archiveFolder, String trashFolder) {
        this.store = store;
        this.archiveFolder = archiveFolder;
        this.trashFolder = trashFolder;
    }

    @Override
    public List<Email> listUnprocessed(int limit) {
        try {
            Folder inbox = store.getFolder(INBOX);
            inbox.open(Folder.READ_ONLY);
            try {
                Message[] unprocessed = inbox.search(new FlagTerm(new Flags(PROCESSED_FLAG), false));
                List<Email> emails = new ArrayList<>();
                for (Message message : unprocessed) {
                    if (emails.size() == limit) break;
                    emails.add(toEmail(message));
                }
                return emails;
            } finally {
                inbox.close(false);
            }
        } catch (MessagingException | IOException e) {
            throw new MailAccessException("Could not list the inbox: " + e.getMessage(), e);
        }
    }

    @Override public void markProcessed(String id) { onMessage(id, (f, m) -> m.setFlags(new Flags(PROCESSED_FLAG), true)); }
    @Override public void archive(String id) { onMessage(id, (f, m) -> moveTo(f, m, archiveFolder)); }
    @Override public void moveToTrash(String id) { onMessage(id, (f, m) -> moveTo(f, m, trashFolder)); }
    @Override public void restoreToInbox(String id) { onMessage(id, (f, m) -> moveTo(f, m, INBOX)); }
    @Override public void addLabel(String id, String label) { onMessage(id, (f, m) -> m.setFlags(new Flags(label), true)); }
    @Override public void removeLabel(String id, String label) { onMessage(id, (f, m) -> m.setFlags(new Flags(label), false)); }

    @FunctionalInterface
    private interface MessageAction {
        void apply(Folder folder, Message message) throws MessagingException;
    }

    /** Finds the message by id in INBOX, then Archive, then Trash, and applies the action to it. */
    private void onMessage(String emailId, MessageAction action) {
        try {
            for (String name : List.of(INBOX, archiveFolder, trashFolder)) {
                Folder folder = store.getFolder(name);
                if (!folder.exists()) continue;
                folder.open(Folder.READ_WRITE);
                try {
                    Message message = find(folder, emailId);
                    if (message != null) {
                        action.apply(folder, message);
                        return;
                    }
                } finally {
                    folder.close(true); // expunges messages flagged as deleted by a move
                }
            }
        } catch (MessagingException e) {
            throw new MailAccessException("Mail operation failed for " + emailId + ": " + e.getMessage(), e);
        }
        throw new MailAccessException("Message not found: " + emailId);
    }

    private void moveTo(Folder source, Message message, String destinationName) throws MessagingException {
        if (source.getFullName().equalsIgnoreCase(destinationName)) return;
        Folder destination = store.getFolder(destinationName);
        if (!destination.exists()) destination.create(Folder.HOLDS_MESSAGES);
        source.copyMessages(new Message[] {message}, destination); // COPY keeps the flags, including ours
        message.setFlag(Flags.Flag.DELETED, true);
    }

    private static Message find(Folder folder, String emailId) throws MessagingException {
        for (Message m : folder.search(searchTerm(emailId))) {
            if (emailId.startsWith(FALLBACK_PREFIX) || emailId.equals(messageIdOf(m))) return m;
        }
        return null;
    }

    private static SearchTerm searchTerm(String emailId) {
        if (emailId.startsWith(FALLBACK_PREFIX)) {
            return new SearchTerm() {
                @Override public boolean match(Message m) {
                    try {
                        return emailId.equals(fallbackId(m));
                    } catch (MessagingException e) {
                        return false;
                    }
                }
            };
        }
        return new HeaderTerm("Message-ID", emailId);
    }

    private static Email toEmail(Message message) throws MessagingException, IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Enumeration<Header> e = message.getAllHeaders(); e.hasMoreElements(); ) {
            Header h = e.nextElement();
            headers.putIfAbsent(h.getName(), h.getValue());
        }
        String messageId = messageIdOf(message);
        String id = messageId != null ? messageId : fallbackId(message);
        return new Email(id, senderOf(message), subjectOf(message), snippet(message), headers);
    }

    private static String messageIdOf(Message message) throws MessagingException {
        String[] ids = message.getHeader("Message-ID");
        return ids != null && ids.length > 0 ? ids[0].trim() : null;
    }

    private static String senderOf(Message message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length == 0) return "";
        return from[0] instanceof InternetAddress ia ? ia.getAddress() : from[0].toString();
    }

    private static String subjectOf(Message message) throws MessagingException {
        return message.getSubject() == null ? "" : message.getSubject();
    }

    private static String snippet(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            String text = String.valueOf(part.getContent()).strip().replaceAll("\\s+", " ");
            return text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS);
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                String s = snippet(multipart.getBodyPart(i));
                if (!s.isEmpty()) return s;
            }
        }
        return "";
    }

    private static String fallbackId(Message message) throws MessagingException {
        try {
            String source = senderOf(message) + "|" + message.getSentDate() + "|" + subjectOf(message);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return FALLBACK_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}