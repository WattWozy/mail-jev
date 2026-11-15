package dev.jefe.mailsorter.application.exception;

public class NotConnectedException extends RuntimeException {
    public NotConnectedException() { super("No mailbox connected: run the connect use case first"); }
}
