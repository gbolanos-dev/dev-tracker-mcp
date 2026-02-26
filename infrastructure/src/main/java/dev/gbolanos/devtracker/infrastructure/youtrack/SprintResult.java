package dev.gbolanos.devtracker.infrastructure.youtrack;

import java.time.LocalDate;
import java.util.List;

public record SprintResult(List<YouTrackIssue> issues, LocalDate startDate, LocalDate endDate) {
}
