package dev.gbolanos.devtracker.infrastructure.youtrack;

import dev.gbolanos.devtracker.domain.enums.CycleOrigin;
import dev.gbolanos.devtracker.domain.enums.TicketState;
import dev.gbolanos.devtracker.domain.enums.TicketType;

public record YouTrackIssue(
        String id,
        String title,
        TicketState state,
        TicketType type,
        int storyPoints,
        CycleOrigin cycleOrigin,
        boolean wasKickbacked,
        String assignee,
        String primaryDev,       // nullable — second ownership field
        String youtrackUrl
) {
}
