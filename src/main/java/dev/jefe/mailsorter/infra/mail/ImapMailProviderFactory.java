package dev.jefe.mailsorter.infra.mail;

import dev.jefe.mailsorter.application.exception.ConnectionFailedException;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.application.port.out.MailProviderFactory;
import dev.jefe.mailsorter.domain.MailboxConfig;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;

import java.util.Properties;

public final class ImapMailProviderFactory implements MailProviderFactory {

    @Override
    public MailProvider connect(MailboxConfig config) {
        boolean ssl = !"false".equalsIgnoreCase(config.options().getOrDefault("ssl", "true"));
        String protocol = ssl ? "imaps" : "imap";
        Properties props = new Properties();
        props.put("mail." + protocol + ".connectiontimeout", "10000");
        props.put("mail." + protocol + ".timeout", "30000");
        try {
            Store store = Session.getInstance(props).getStore(protocol);
            store.connect(config.host(), config.port(), config.username(), config.secret());
            return new ImapMailProvider(store);
        } catch (MessagingException e) {
            throw new ConnectionFailedException("Could not connect to " + config.host() + ": " + e.getMessage(), e);
        }
    }
}
