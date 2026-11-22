package dev.jefe.mailsorter.application.exception;

public final class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String message) { super(message); }
}