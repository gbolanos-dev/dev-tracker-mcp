package dev.gbolanos.devtracker.application;

import dev.gbolanos.devtracker.domain.model.CodeReview;
import dev.gbolanos.devtracker.infrastructure.github.GitHubClient;
import dev.gbolanos.devtracker.infrastructure.youtrack.SprintResult;
import dev.gbolanos.devtracker.infrastructure.youtrack.YouTrackClient;

import java.time.LocalDate;
import java.util.List;

public class GetCodeReviews {

    private final GitHubClient gitHubClient;
    private final YouTrackClient youTrackClient;

    public GetCodeReviews(GitHubClient gitHubClient, YouTrackClient youTrackClient) {
        this.gitHubClient = gitHubClient;
        this.youTrackClient = youTrackClient;
    }

    public List<CodeReview> execute(LocalDate startDate, LocalDate endDate, String sprintName) {
        LocalDate resolvedStart = startDate;
        LocalDate resolvedEnd = endDate;

        if (sprintName != null && !sprintName.isBlank()) {
            SprintResult sprint = youTrackClient.fetchSprintWithDates(null, sprintName, null);
            resolvedStart = sprint.startDate();
            resolvedEnd = sprint.endDate();
        }

        return gitHubClient.fetchReviews(resolvedStart, resolvedEnd);
    }
}
