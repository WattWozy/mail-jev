package dev.jefe.mailsorter.infra.mail;

import dev.jefe.mailsorter.application.exception.MailAccessException;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.domain.Email;
import jakarta.mail.*;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.ParseException;
import jakarta.mail.search.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * IMAP-backed mail provider.
 *
 * Performance notes: everything {@link #toEmail} reads (headers, body structure) is prefetched in ONE
 * batched FETCH in {@link #listUnprocessed}; the snippet reads only a few hundred bytes of the first
 * text/plain part. For that to be cheap, the Session should set
 * mail.imap.partialfetch=true, mail.imap.fetchsize=2048 and mail.imap.peek=true.
 */
public final class ImapMailProvider implements MailProvider {

    static final String PROCESSED_FLAG = "mail-sorter-processed";
    private static final String INBOX = "INBOX";
    private static final String FALLBACK_PREFIX = "fallback:";
    private static final int SNIPPET_CHARS = 200;
    private static final int[] LOOKBACK_DAYS = {30, 90, 365};

    /** Headers exposed to the classifier. All of them are prefetched in a single batch. */
    private static final String[] HEADER_NAMES = {
            "From", "To", "Subject", "Date", "Message-ID", "Reply-To",
            "List-Unsubscribe", "List-Id", "Precedence", "Auto-Submitted"
    };

    private final Store store;
    private final String archiveFolder;
    private final String trashFolder;
    private final String host;
    private final String username;

    ImapMailProvider(Store store, String host, String username) {
        this(store, "Archive", "Trash", host, username);
    }

    ImapMailProvider(Store store, String archiveFolder, String trashFolder, String host, String username) {
        this.store = store;
        this.archiveFolder = archiveFolder;
        this.trashFolder = trashFolder;
        this.host = host;
        this.username = username;
    }

    @Override
    public List<Email> listUnprocessed(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        try {
            Folder inbox = store.getFolder(INBOX);
            inbox.open(Folder.READ_ONLY);
            try {
                SearchTerm notProcessed = new FlagTerm(new Flags(PROCESSED_FLAG), false);

                long t0 = System.nanoTime();
                Message[] matches = searchWithProgressiveWindow(inbox, notProcessed, limit);
                long t1 = System.nanoTime();
                if (matches.length == 0) {
                    return List.of();
                }

                // Newest first.
                int count = Math.min(limit, matches.length);
                Message[] latestSlice = new Message[count];
                for (int i = 0; i < count; i++) {
                    latestSlice[i] = matches[matches.length - 1 - i];
                }

                FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE);
                fp.add(FetchProfile.Item.FLAGS);
                fp.add(FetchProfile.Item.CONTENT_INFO); // body structure, so snippet() needs no extra round trip
                for (String header : HEADER_NAMES) {
                    fp.add(header);
                }
                inbox.fetch(latestSlice, fp);
                long t2 = System.nanoTime();

                List<Email> emails = new ArrayList<>(count);
                for (Message message : latestSlice) {
                    emails.add(toEmail(message));
                }
                long t3 = System.nanoTime();

                System.out.printf("search=%dms fetch=%dms toEmail=%dms%n",
                        (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000);
                return emails;
            } finally {
                inbox.close(false);
            }
        } catch (MessagingException | IOException e) {
            throw new MailAccessException("Could not list the inbox: " + e.getMessage(), e);
        }
    }

    @Override public String accountId() { return username + "@" + host; }
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
        // getHeader(name, null) is served from the prefetched headers; getAllHeaders() would hit the server again.
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : HEADER_NAMES) {
            String value = Arrays.toString(message.getHeader(name));
            if (value != null) {
                headers.put(name, value);
            }
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

    /** First text/plain content, skipping attachments; reads only a short prefix of the part. */
    private static String snippet(Part part) throws MessagingException, IOException {
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            return "";
        }
        if (part.isMimeType("text/plain")) {
            return readPrefix(part);
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

    /** Reads at most SNIPPET_CHARS * 4 raw bytes (decoded from base64/quoted-printable by the stream). */
    private static String readPrefix(Part part) throws MessagingException, IOException {
        Charset charset = StandardCharsets.UTF_8;
        try {
            String declared = new ContentType(part.getContentType()).getParameter("charset");
            if (declared != null) charset = Charset.forName(declared);
        } catch (ParseException | IllegalArgumentException ignored) {
            // unknown or malformed charset: fall back to UTF-8
        }

        try (InputStream in = part.getInputStream()) {
            byte[] buffer = in.readNBytes(SNIPPET_CHARS * 4);
            String text = new String(buffer, charset).strip().replaceAll("\\s+", " ");
            return text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS);
        }
    }

    private static Message[] searchWithProgressiveWindow(Folder inbox, SearchTerm baseTerm, int limit)
            throws MessagingException {
        Instant now = Instant.now();

        for (int days : LOOKBACK_DAYS) {
            Date since = Date.from(now.minus(days, ChronoUnit.DAYS));
            SearchTerm term = new AndTerm(baseTerm, new ReceivedDateTerm(ComparisonTerm.GE, since));
            Message[] result = inbox.search(term);
            if (result.length >= limit) {
                return result;
            }
        }

        // Fewer than `limit` within the last year: search the whole INBOX.
        return inbox.search(baseTerm);
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