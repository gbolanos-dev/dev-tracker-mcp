package dev.gbolanos.devtracker.infrastructure.youtrack;

import java.time.LocalDate;
import java.util.List;

/**
 * Result of a sprint-based ticket query.
 * {@code startDate} and {@code endDate} may be null when sprint date resolution fails
 * (e.g., board permissions). Tickets are still returned via sprint field query.
 */
public record SprintResult(List<YouTrackIssue> issues, LocalDate startDate, LocalDate endDate) {
}
