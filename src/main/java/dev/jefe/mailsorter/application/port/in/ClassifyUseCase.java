package dev.jefe.mailsorter.application.port.in;

public interface ClassifyUseCase {
    ClassificationReport execute(ClassifyCommand command);
}
