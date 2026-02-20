package dev.gbolanos.devtracker.domain.enums;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum TicketState {
    OPEN("Open"),
    DEV_READY("Dev Ready"),
    IN_PROGRESS("In Progress"),
    PENDING_REVIEW("Pending Review"),
    PENDING_QA("Pending QA"),
    MERGED_CLOSED("Merged & Closed"),
    KICKBACKED("Kickbacked");

    private static final Set<TicketState> COMPLETED_STATES = Set.of(
            PENDING_REVIEW, PENDING_QA, MERGED_CLOSED
    );

    private static final Map<String, TicketState> BY_LABEL = Stream.of(values())
            .collect(Collectors.toMap(s -> s.label.toLowerCase(), s -> s));

    private final String label;

    TicketState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isCompleted() {
        return COMPLETED_STATES.contains(this);
    }

    public boolean isKickbacked() {
        return this == KICKBACKED;
    }

    public static TicketState fromLabel(String label) {
        TicketState state = BY_LABEL.get(label.toLowerCase());
        if (state == null) {
            throw new IllegalArgumentException("Unknown ticket state: " + label);
        }
        return state;
    }
}
