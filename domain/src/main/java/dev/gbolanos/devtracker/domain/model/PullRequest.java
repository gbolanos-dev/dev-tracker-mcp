package dev.gbolanos.devtracker.domain.model;

import java.time.LocalDate;

public record PullRequest(
        int number,
        String repo,
        String title,
        String author,
        int linesAdded,
        int linesDeleted,
        int filesChanged,
        String linkedTicketId,   // nullable
        LocalDate mergedAt,
        String url
) {
}
