package dev.gbolanos.devtracker.application;

import dev.gbolanos.devtracker.domain.model.CodeReview;
import dev.gbolanos.devtracker.infrastructure.github.GitHubClient;

import java.time.LocalDate;
import java.util.List;

public class GetCodeReviews {

    private final GitHubClient gitHubClient;

    public GetCodeReviews(GitHubClient gitHubClient) {
        this.gitHubClient = gitHubClient;
    }

    public List<CodeReview> execute(LocalDate startDate, LocalDate endDate) {
        return gitHubClient.fetchReviews(startDate, endDate);
    }
}
