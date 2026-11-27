package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.exception.PlanNotFoundException;
import dev.jefe.mailsorter.application.port.in.dto.ApplyCommand;
import dev.jefe.mailsorter.application.port.in.ApplyPlanUseCase;
import dev.jefe.mailsorter.application.port.in.dto.ApplyResult;
import dev.jefe.mailsorter.application.port.in.dto.Plan;
import dev.jefe.mailsorter.application.port.out.*;
import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.application.port.out.dto.MailOperation;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public final class ApplyPlanService implements ApplyPlanUseCase {

    public static final String REVIEW_LABEL = "mail-sorter/review";

    private final ConnectionStore store;
    private final PlanStore plans;
    private final AuditLog audit;
    private final ActivityRepository activity;
    private final Clock clock;

    public ApplyPlanService(ConnectionStore store, PlanStore plans, AuditLog audit,
                            ActivityRepository activity, Clock clock) {
        this.store = store; this.plans = plans; this.audit = audit;
        this.activity = activity; this.clock = clock;
    }

    @Override
    public ApplyResult execute(ApplyCommand command) {
        MailProvider mailbox = store.current().orElseThrow(NotConnectedException::new);
        Plan plan = plans.find(command.planId())
                .orElseThrow(() -> new PlanNotFoundException(command.planId()));
        validate(plan, command.overrides());

        // Build the final list of operations (respecting overrides)
        List<MailOperation> ops = plan.entries().stream()
                .map(e -> operationFor(e, command.overrides()))
                .toList();

        // One bulk round-trip (the fast path you built this morning)
        Map<String, String> failed = mailbox.applyAll(ops, REVIEW_LABEL);

        Map<Action, Long> applied = new EnumMap<>(Action.class);
        List<ApplyResult.Failure> failures = new ArrayList<>();
        Instant now = clock.instant();

        for (Plan.Entry e : plan.entries()) {
            Action action = command.overrides().getOrDefault(e.emailId(), e.action());
            if (failed.containsKey(e.emailId())) {
                failures.add(new ApplyResult.Failure(e.emailId(), failed.get(e.emailId())));
            } else {
                boolean overridden = action != e.action();
                audit.record(e.emailId(), e.classification(), action, overridden);
                recordActivity(now, mailbox.accountId(), e, action, overridden);
                applied.merge(action, 1L, Long::sum);
            }
        }

        plans.remove(plan.id());
        return new ApplyResult(applied, List.copyOf(failures));
    }

    /** Best effort: the mailbox has already changed, a bookkeeping failure must not fail the apply. */
    private void recordActivity(Instant at, String accountId, Plan.Entry e, Action action, boolean overridden) {
        var c = e.classification();
        String folder = action == Action.MOVE_TO_FOLDER ? c.folder() : null;
        try {
            activity.record(new ActivityEntry(at, accountId, e.emailId(), e.from(), e.subject(),
                    c.category(), c.confidence(), c.reason(), action, c.source(), overridden, folder));
        } catch (RuntimeException ex) {
            AppLog.warn(ApplyPlanService.class, "Could not record activity for " + e.emailId() + ": " + ex);
        }
    }

    /** The destination folder always comes from the plan: an override can only pick the action. */
    private static MailOperation operationFor(Plan.Entry e, Map<String, Action> overrides) {
        Action action = overrides.getOrDefault(e.emailId(), e.action());
        return new MailOperation(e.emailId(), action, e.classification().folder());
    }

    private static void validate(Plan plan, Map<String, Action> overrides) {
        Map<String, Plan.Entry> byId = plan.entries().stream()
                .collect(Collectors.toMap(Plan.Entry::emailId, e -> e, (a, b) -> a));
        overrides.forEach((id, action) -> {
            Plan.Entry entry = byId.get(id);
            if (entry == null) throw new IllegalArgumentException("Override for an email not in the plan: " + id);
            if (action == Action.MOVE_TO_FOLDER && entry.classification().folder() == null) {
                throw new IllegalArgumentException("Cannot move " + id + " to a folder: the plan names no folder for it");
            }
        });
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