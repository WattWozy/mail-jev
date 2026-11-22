package dev.jefe.mailsorter.infra.mail;

import dev.jefe.mailsorter.application.exception.MailAccessException;
import dev.jefe.mailsorter.application.port.out.MailOperation;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.Email;
import dev.jefe.mailsorter.domain.EmailContent;
import dev.jefe.mailsorter.infra.logging.AppLog;
import jakarta.mail.*;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.ParseException;
import jakarta.mail.search.*;
import org.eclipse.angus.mail.imap.IMAPFolder;

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
    private static final int SNIPPET_CHARS = 160;
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
    public void close() {
        try {
            store.close();
        } catch (MessagingException e) {
            AppLog.error(ImapMailProvider.class, "Could not close IMAP store", e);
        }
    }

    @Override
    public EmailContent read(String emailId) {
        try {
            for (String folderName : List.of(INBOX, archiveFolder, trashFolder)) {
                Folder folder = store.getFolder(folderName);

                if (!folder.exists()) continue;

                folder.open(Folder.READ_ONLY);
                try {
                    Message message = find(folder, emailId);

                    if (message != null) {
                        Date sentDate = message.getSentDate();

                        return new EmailContent(
                                emailId,
                                senderOf(message),
                                subjectOf(message),
                                sentDate == null ? "" : sentDate.toInstant().toString(),
                                extractText(message)
                        );
                    }
                } catch (IOException e) {
                    var ex = new RuntimeException(e);
                    AppLog.error(ImapMailProvider.class, ex.getMessage(), ex);
                    throw ex;
                } finally {
                    folder.close(false);
                }
            }
        } catch (MessagingException e) {
            var ex = new MailAccessException(
                    "Could not read email " + emailId + ": " + e.getMessage(), e);
            AppLog.error(ImapMailProvider.class, ex.getMessage(), ex);
            throw ex;
        }

        var ex = new MailAccessException("Message not found: " + emailId);
        AppLog.error(ImapMailProvider.class, ex.getMessage(), ex);
        throw ex;
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

                // Newest first
                int count = Math.min(limit, matches.length);
                Message[] latestSlice = new Message[count];
                for (int i = 0; i < count; i++) {
                    latestSlice[i] = matches[matches.length - 1 - i];
                }

                // 1) Structure + headers
                FetchProfile structure = new FetchProfile();
                structure.add(FetchProfile.Item.ENVELOPE);
                structure.add(FetchProfile.Item.FLAGS);
                structure.add(FetchProfile.Item.CONTENT_INFO);
                for (String header : HEADER_NAMES) {
                    structure.add(header);
                }
                inbox.fetch(latestSlice, structure);
                long t2 = System.nanoTime();

                // 2) Force full content into cache (batch) so snippet() becomes local
                FetchProfile content = new FetchProfile();
                content.add(IMAPFolder.FetchProfileItem.MESSAGE);
                inbox.fetch(latestSlice, content);
                long t2b = System.nanoTime();

                List<Email> emails = new ArrayList<>(count);
                for (Message message : latestSlice) {
                    emails.add(toEmail(message));
                }
                long t3 = System.nanoTime();

                System.out.printf("search=%dms structure=%dms content=%dms toEmail=%dms%n",
                        (t1 - t0) / 1_000_000,
                        (t2 - t1) / 1_000_000,
                        (t2b - t2) / 1_000_000,
                        (t3 - t2b) / 1_000_000);
                return emails;
            } finally {
                inbox.close(false);
            }
        } catch (MessagingException | IOException e) {
            var ex = new MailAccessException("Could not list the inbox: " + e.getMessage(), e);
            AppLog.error(ImapMailProvider.class, ex.getMessage(), ex);
            throw ex;
        }
    }

    @Override public String accountId() { return username + "@" + host; }
    @Override public void markProcessed(String id) { onMessage(id, (f, m) -> m.setFlags(new Flags(PROCESSED_FLAG), true)); }
    @Override public void archive(String id) { onMessage(id, (f, m) -> moveTo(f, m, archiveFolder)); }
    @Override public void moveToTrash(String id) { onMessage(id, (f, m) -> moveTo(f, m, trashFolder)); }
    @Override public void restoreToInbox(String id) { onMessage(id, (f, m) -> moveTo(f, m, INBOX)); }
    @Override public void addLabel(String id, String label) { onMessage(id, (f, m) -> m.setFlags(new Flags(label), true)); }
    @Override public void removeLabel(String id, String label) { onMessage(id, (f, m) -> m.setFlags(new Flags(label), false)); }
    @Override
    public Set<String> applyAll(List<MailOperation> ops, String reviewLabel) {
        Set<String> failed = new HashSet<>();
        if (ops.isEmpty()) return failed;

        try {
            IMAPFolder inbox = (IMAPFolder) store.getFolder(INBOX);
            inbox.open(Folder.READ_WRITE);
            try {
                Map<String, Message> byId = resolve(inbox, ops);

                Map<Action, List<Message>> byAction = new EnumMap<>(Action.class);
                Map<Message, String> idOf = new IdentityHashMap<>();
                for (MailOperation op : ops) {
                    Message m = byId.get(op.emailId());
                    if (m == null) { failed.add(op.emailId()); continue; }
                    idOf.put(m, op.emailId());
                    byAction.computeIfAbsent(op.action(), k -> new ArrayList<>()).add(m);
                }

                for (Map.Entry<Action, List<Message>> entry : byAction.entrySet()) {
                    Message[] group = entry.getValue().toArray(Message[]::new);
                    try {
                        // Flag first: COPY/MOVE preserves flags, so moved mail keeps the marker.
                        inbox.setFlags(group, new Flags(PROCESSED_FLAG), true);
                        switch (entry.getKey()) {
                            case ARCHIVE -> moveAll(inbox, group, archiveFolder);
                            case MOVE_TO_TRASH -> moveAll(inbox, group, trashFolder);
                            case FLAG_FOR_REVIEW -> inbox.setFlags(group, new Flags(reviewLabel), true);
                            case KEEP -> { }
                        }
                    } catch (MessagingException e) {
                        for (Message m : group) failed.add(idOf.get(m));
                        try { // best effort: let the next run retry them
                            inbox.setFlags(group, new Flags(PROCESSED_FLAG), false);
                        } catch (MessagingException ignored) { }
                    }
                }
            } finally {
                inbox.close(true); // single expunge
            }
        } catch (MessagingException e) {
            var ex = new MailAccessException("Could not apply changes: " + e.getMessage(), e);
            AppLog.error(ImapMailProvider.class, ex.getMessage(), ex);
            throw ex;
        }
        return failed;
    }

    private static void move(IMAPFolder from, List<Message> msgs, Folder to) throws MessagingException {
        if (msgs == null || msgs.isEmpty()) return;
        from.moveMessages(msgs.toArray(Message[]::new), to); // uses MOVE if the server supports it, else COPY+DELETE
    }
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
            var ex = new MailAccessException("Mail operation failed for " + emailId + ": " + e.getMessage(), e);
            AppLog.error(ImapMailProvider.class, ex.getMessage(), ex);
            throw ex;
        }
        var ex = new MailAccessException("Message not found: " + emailId);
        AppLog.error(ImapMailProvider.class, ex.getMessage(), ex);
        throw ex;
    }

    /** Resolves all ids with as few round trips as possible (one SEARCH + one FETCH). */
    private Map<String, Message> resolve(IMAPFolder inbox, List<MailOperation> ops) throws MessagingException {
        Map<String, Message> found = new HashMap<>();

        List<String> messageIds = ops.stream().map(MailOperation::emailId)
                .filter(id -> !id.startsWith(FALLBACK_PREFIX)).distinct().toList();
        if (!messageIds.isEmpty()) {
            SearchTerm[] terms = messageIds.stream()
                    .map(id -> new HeaderTerm("Message-ID", id)).toArray(SearchTerm[]::new);
            Message[] hits = inbox.search(terms.length == 1 ? terms[0] : new OrTerm(terms));

            FetchProfile fp = new FetchProfile();
            fp.add("Message-ID");
            inbox.fetch(hits, fp);
            for (Message m : hits) {
                String id = messageIdOf(m);
                if (id != null) found.putIfAbsent(id, m);
            }
        }

        // Rare: mails without a Message-ID. Only scan the still-unprocessed ones, not the whole inbox.
        if (ops.stream().anyMatch(o -> o.emailId().startsWith(FALLBACK_PREFIX))) {
            Message[] candidates = inbox.search(new FlagTerm(new Flags(PROCESSED_FLAG), false));
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add("Message-ID");
            inbox.fetch(candidates, fp);
            for (Message m : candidates) {
                if (messageIdOf(m) == null) found.putIfAbsent(fallbackId(m), m);
            }
        }
        return found;
    }

    private void moveAll(IMAPFolder from, Message[] msgs, String destinationName) throws MessagingException {
        if (from.getFullName().equalsIgnoreCase(destinationName)) return;
        Folder destination = store.getFolder(destinationName);
        if (!destination.exists()) destination.create(Folder.HOLDS_MESSAGES);
        from.moveMessages(msgs, destination); // MOVE if the server supports it, else COPY + DELETE
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
        for (String name : HEADER_NAMES) {
            String[] values = message.getHeader(name);
            if (values != null && values.length > 0) {
                headers.put(name, String.join(", ", values));
            }
        }
        String messageId = messageIdOf(message);
        String id = messageId != null ? messageId : fallbackId(message);
        return new Email(id, senderOf(message), subjectOf(message),
                snippet(extractText(message)), headers);
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

    /** Extract the full plain-text body, using HTML only as fallback. */
    private static String extractText(Part part)
            throws MessagingException, IOException {

        String plainText = extractMimeText(part, "text/plain");
        if (!plainText.isBlank()) {
            return plainText;
        }

        String html = extractMimeText(part, "text/html");
        return html.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String extractMimeText(Part part, String mimeType)
            throws MessagingException, IOException {

        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                || part.getFileName() != null) {
            return "";
        }

        if (part.isMimeType(mimeType)) {
            return readTextPart(part);
        }

        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();

            for (int i = 0; i < multipart.getCount(); i++) {
                String text = extractMimeText(
                        multipart.getBodyPart(i), mimeType);

                if (!text.isBlank()) {
                    return text;
                }
            }
        }

        return "";
    }

    /** Reads the entire text part using its declared charset. */
    private static String readTextPart(Part part)
            throws MessagingException, IOException {

        Charset charset = StandardCharsets.UTF_8;

        try {
            String declared =
                    new ContentType(part.getContentType()).getParameter("charset");

            if (declared != null) {
                charset = Charset.forName(declared);
            }
        } catch (ParseException | IllegalArgumentException ignored) {
            // Fall back to UTF-8.
        }

        try (InputStream in = part.getInputStream()) {
            return new String(in.readAllBytes(), charset).strip();
        }
    }

    /** Creates the short preview used in the email list. */
    private static String snippet(String body) {
        String normalized = body.replaceAll("\\s+", " ").strip();

        return normalized.length() <= SNIPPET_CHARS
                ? normalized
                : normalized.substring(0, SNIPPET_CHARS).stripTrailing();
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