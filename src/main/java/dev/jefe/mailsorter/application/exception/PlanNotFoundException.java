package dev.jefe.mailsorter.application.exception;

public class PlanNotFoundException extends RuntimeException {
    public PlanNotFoundException(String id) {
        super("Unknown or already applied plan: " + id);
    }
}
