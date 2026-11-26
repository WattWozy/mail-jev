package dev.jefe.mailsorter.application.port.out;

/** Driven port. Pushes a short notification to whoever is listening (UI, tray). Must never throw. */
public interface EventPublisher {
    void publish(String type, String message);
}
