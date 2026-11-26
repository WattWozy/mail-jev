package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.application.port.in.dto.ApplyCommand;
import dev.jefe.mailsorter.application.port.in.dto.ApplyResult;

public interface ApplyPlanUseCase { ApplyResult execute(ApplyCommand command); }