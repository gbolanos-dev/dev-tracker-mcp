package dev.gbolanos.devtracker.application;

import dev.gbolanos.devtracker.domain.enums.EffortLevel;
import dev.gbolanos.devtracker.domain.enums.TicketState;
import dev.gbolanos.devtracker.domain.model.PullRequest;
import dev.gbolanos.devtracker.domain.model.Ticket;
import dev.gbolanos.devtracker.domain.service.EffortCalculator;
import dev.gbolanos.devtracker.infrastructure.github.GitHubClient;
import dev.gbolanos.devtracker.infrastructure.youtrack.SprintResult;
import dev.gbolanos.devtracker.infrastructure.youtrack.YouTrackClient;
import dev.gbolanos.devtracker.infrastructure.youtrack.YouTrackIssue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GetTicketDetails {

    private final YouTrackClient youTrackClient;
    private final GitHubClient gitHubClient;

    public GetTicketDetails(YouTrackClient youTrackClient, GitHubClient gitHubClient) {
        this.youTrackClient = youTrackClient;
        this.gitHubClient = gitHubClient;
    }

    public TicketDetailsResult execute(LocalDate startDate, LocalDate endDate,
                                       String sprintName, String project) {
        List<YouTrackIssue> issues;
        LocalDate resolvedStart;
        LocalDate resolvedEnd;

        if (sprintName != null && !sprintName.isBlank()) {
            SprintResult sprint = youTrackClient.fetchSprintWithDates(null, sprintName, project);
            issues = sprint.issues();
            resolvedStart = sprint.startDate();
            resolvedEnd = sprint.endDate();
        } else {
            issues = youTrackClient.fetchIssues(startDate, endDate, project);
            resolvedStart = startDate;
            resolvedEnd = endDate;
        }

        var prs = (resolvedStart != null && resolvedEnd != null)
                ? gitHubClient.fetchMergedPRs(resolvedStart, resolvedEnd)
                : List.<PullRequest>of();

        Map<String, List<PullRequest>> prsByTicket = prs.stream()
                .filter(pr -> pr.linkedTicketId() != null)
                .collect(Collectors.groupingBy(PullRequest::linkedTicketId));

        List<Ticket> tickets = issues.stream()
                .map(issue -> toTicket(issue, prsByTicket.getOrDefault(issue.id(), List.of())))
                .toList();

        return new TicketDetailsResult(tickets, resolvedStart, resolvedEnd);
    }

    private Ticket toTicket(YouTrackIssue issue, List<PullRequest> linkedPRs) {
        EffortLevel effort = linkedPRs.stream()
                .map(pr -> EffortCalculator.fromPullRequest(pr.linesAdded(), pr.linesDeleted(), pr.filesChanged()))
                .max(Enum::compareTo)
                .orElse(EffortLevel.TRIVIAL);

        TicketState state = issue.wasKickbacked() ? TicketState.KICKBACKED : issue.state();

        return new Ticket(
                issue.id(),
                issue.title(),
                state,
                issue.type(),
                issue.storyPoints(),
                issue.cycleOrigin(),
                effort,
                issue.assignee(),
                null,
                linkedPRs,
                issue.youtrackUrl()
        );
    }
}
