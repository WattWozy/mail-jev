package dev.jefe.mailsorter.application.exception;

/** A folder cannot be deleted while rules still file mail into it. */
public class FolderInUseException extends RuntimeException {
    public FolderInUseException(String message) {
        super(message);
    }
}
