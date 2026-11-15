package dev.jefe.mailsorter.application.exception;

public class MailAccessException extends RuntimeException {
    public MailAccessException(String message) { super(message); }
    public MailAccessException(String message, Throwable cause) { super(message, cause); }
}
