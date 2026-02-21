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

    public CycleMetrics execute(LocalDate startDate, LocalDate endDate) {
        var tickets = getTicketDetails.execute(startDate, endDate);
        var reviews = gitHubClient.fetchReviews(startDate, endDate);
        return PointsCalculator.calculate(tickets, reviews.size());
    }
}
