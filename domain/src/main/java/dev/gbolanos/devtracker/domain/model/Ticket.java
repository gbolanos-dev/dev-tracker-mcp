package dev.gbolanos.devtracker.domain.model;

import dev.gbolanos.devtracker.domain.enums.CycleOrigin;
import dev.gbolanos.devtracker.domain.enums.EffortLevel;
import dev.gbolanos.devtracker.domain.enums.TicketState;
import dev.gbolanos.devtracker.domain.enums.TicketType;

import java.util.List;

public record Ticket(
        String id,               // e.g. "PROJ-123"
        String title,
        TicketState state,
        TicketType type,
        int devPoints,
        CycleOrigin cycleOrigin,
        EffortLevel effortLevel,
        String notes,            // nullable
        List<PullRequest> linkedPRs,
        String youtrackUrl
) {
}
