package dev.gbolanos.devtracker.domain.model;

import dev.gbolanos.devtracker.domain.enums.EffortLevel;

import java.time.LocalDate;

public record CodeReview(
        int prNumber,
        String repo,
        String prTitle,
        EffortLevel effortLevel,
        String summary,          // nullable
        LocalDate reviewedAt,
        String url,
        int linesReviewed,
        int filesReviewed
) {
}
