package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.application.port.in.dto.ClassifyCommand;
import dev.jefe.mailsorter.application.port.in.dto.Plan;

public interface ClassifyUseCase { Plan execute(ClassifyCommand command); }
