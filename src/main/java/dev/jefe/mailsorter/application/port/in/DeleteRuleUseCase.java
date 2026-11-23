package dev.jefe.mailsorter.application.port.in;

public interface DeleteRuleUseCase {
    void execute(String ruleId);
}