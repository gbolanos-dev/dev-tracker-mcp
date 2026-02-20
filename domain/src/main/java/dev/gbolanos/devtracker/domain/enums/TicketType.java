package dev.gbolanos.devtracker.domain.enums;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum TicketType {
    STORY("Story"),
    BUG("Bug"),
    TASK("Task"),
    SPIKE("Spike"),
    SUBTASK("Subtask"),
    FEATURE("Feature"),
    REFACTOR("Refactor");

    private static final Map<String, TicketType> BY_LABEL = Stream.of(values())
            .collect(Collectors.toMap(t -> t.label.toLowerCase(), t -> t));

    private final String label;

    TicketType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static TicketType fromLabel(String label) {
        TicketType type = BY_LABEL.get(label.toLowerCase());
        if (type == null) {
            throw new IllegalArgumentException("Unknown ticket type: " + label);
        }
        return type;
    }
}
