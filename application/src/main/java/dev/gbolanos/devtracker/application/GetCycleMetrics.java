package dev.gbolanos.devtracker.application;

import dev.gbolanos.devtracker.domain.model.CycleMetrics;
import dev.gbolanos.devtracker.domain.service.PointsCalculator;
import dev.gbolanos.devtracker.infrastructure.github.GitHubClient;

import java.time.LocalDate;

public class GetCycleMetrics {

    private final GetTicketDetails getTicketDetails;
    private final GitHubClient gitHubClient;

    public GetCycleMetrics(GetTicketDetails getTicketDetails, GitHubClient gitHubClient) {
        this.getTicketDetails = getTicketDetails;
        this.gitHubClient = gitHubClient;
    }

    public CycleMetrics execute(LocalDate startDate, LocalDate endDate,
                                String sprintName, String project) {
        var result = getTicketDetails.execute(startDate, endDate, sprintName, project);
        var reviews = gitHubClient.fetchReviews(result.resolvedStartDate(), result.resolvedEndDate());
        return PointsCalculator.calculate(result.tickets(), reviews.size());
    }
}
