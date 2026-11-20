package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.exception.MailAccessException;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.exception.PlanNotFoundException;
import dev.jefe.mailsorter.application.port.in.ApplyCommand;
import dev.jefe.mailsorter.application.port.in.ApplyPlanUseCase;
import dev.jefe.mailsorter.application.port.in.ApplyResult;
import dev.jefe.mailsorter.application.port.in.Plan;
import dev.jefe.mailsorter.application.port.out.AuditLog;
import dev.jefe.mailsorter.application.port.out.ConnectionStore;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.application.port.out.PlanStore;
import dev.jefe.mailsorter.domain.Action;

import java.util.*;
import java.util.stream.Collectors;

public final class ApplyPlanService implements ApplyPlanUseCase {

    public static final String REVIEW_LABEL = "mail-sorter/review";

    private final ConnectionStore store;
    private final PlanStore plans;
    private final AuditLog audit;

    public ApplyPlanService(ConnectionStore store, PlanStore plans, AuditLog audit) {
        this.store = store; this.plans = plans; this.audit = audit;
    }

    @Override
    public ApplyResult execute(ApplyCommand command) {
        MailProvider mailbox = store.current().orElseThrow(NotConnectedException::new);
        Plan plan = plans.find(command.planId()).orElseThrow(() -> new PlanNotFoundException(command.planId()));
        validate(plan, command.overrides());

        Map<Action, Long> applied = new EnumMap<>(Action.class);
        List<ApplyResult.Failure> failures = new ArrayList<>();
        for (Plan.Entry e : plan.entries()) {
            Action action = command.overrides().getOrDefault(e.emailId(), e.action());
            try {
                perform(mailbox, e.emailId(), action);
                mailbox.markProcessed(e.emailId());
                audit.record(e.emailId(), e.classification(), action, action != e.action());
                applied.merge(action, 1L, Long::sum);
            } catch (MailAccessException ex) {
                failures.add(new ApplyResult.Failure(e.emailId(), ex.getMessage()));
            }
        }
        plans.remove(plan.id());
        return new ApplyResult(applied, List.copyOf(failures));
    }

    private static void validate(Plan plan, Map<String, Action> overrides) {
        Set<String> ids = plan.entries().stream().map(Plan.Entry::emailId).collect(Collectors.toSet());
        for (String id : overrides.keySet()) {
            if (!ids.contains(id)) throw new IllegalArgumentException("Override for an email not in the plan: " + id);
        }
    }

    private static void perform(MailProvider mailbox, String id, Action action) {
        switch (action) {
            case ARCHIVE -> mailbox.archive(id);
            case MOVE_TO_TRASH -> mailbox.moveToTrash(id);
            case FLAG_FOR_REVIEW -> mailbox.addLabel(id, REVIEW_LABEL);
            case KEEP -> { }
        }
    }
}