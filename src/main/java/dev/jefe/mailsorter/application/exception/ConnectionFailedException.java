package dev.jefe.mailsorter.application.exception;

public class ConnectionFailedException extends RuntimeException {
    public ConnectionFailedException(String message) { super(message); }
    public ConnectionFailedException(String message, Throwable cause) { super(message, cause); }
}
