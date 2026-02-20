package dev.gbolanos.devtracker.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record SprintSummary(
        String sprintName,
        LocalDate startDate,
        LocalDate endDate,
        Map<String, List<Ticket>> ticketsByAssignee,
        Map<String, List<PullRequest>> mergedPRsByAuthor,
        int totalPointsCompleted,
        int totalTicketsClosed
) {
}
