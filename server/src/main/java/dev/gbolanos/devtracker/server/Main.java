package dev.gbolanos.devtracker.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.gbolanos.devtracker.application.GetCodeReviews;
import dev.gbolanos.devtracker.application.GetCycleMetrics;
import dev.gbolanos.devtracker.application.GetTicketDetails;
import dev.gbolanos.devtracker.infrastructure.github.GitHubClient;
import dev.gbolanos.devtracker.infrastructure.youtrack.YouTrackClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("Dev Tracker MCP server starting");

        var youTrackClient = new YouTrackClient();
        var gitHubClient = new GitHubClient();

        var getTicketDetails = new GetTicketDetails(youTrackClient, gitHubClient);
        var getCycleMetrics = new GetCycleMetrics(getTicketDetails, gitHubClient);
        var getCodeReviews = new GetCodeReviews(gitHubClient, youTrackClient);

        var registry = new ToolRegistry();
        var dateRangeSchema = buildDateRangeSchema();

        registry.register(new ToolDefinition(
                "get_cycle_metrics",
                "Aggregate metrics for a work cycle (tickets, points, reviews). Prefer using sprintName over date range.",
                dateRangeSchema,
                a -> {
                    var params = parseQueryParams(a);
                    return getCycleMetrics.execute(params.startDate, params.endDate,
                            params.sprintName, params.project);
                }
        ));

        registry.register(new ToolDefinition(
                "get_ticket_details",
                "Detailed ticket list with linked PRs and effort levels. Prefer using sprintName over date range.",
                dateRangeSchema,
                a -> {
                    var params = parseQueryParams(a);
                    return getTicketDetails.execute(params.startDate, params.endDate,
                            params.sprintName, params.project);
                }
        ));

        registry.register(new ToolDefinition(
                "get_code_reviews",
                "Code reviews performed by the user. Prefer using sprintName over date range.",
                dateRangeSchema,
                a -> {
                    var params = parseQueryParams(a);
                    return getCodeReviews.execute(params.startDate, params.endDate,
                            params.sprintName);
                }
        ));

        var handler = new JsonRpcHandler(registry);
        var server = new McpServer(handler);
        server.start();
    }

    private record QueryParams(LocalDate startDate, LocalDate endDate,
                                String sprintName, String project) {
    }

    private static QueryParams parseQueryParams(JsonObject args) {
        String sprintName = optionalString(args, "sprintName");
        String project = optionalString(args, "project");
        LocalDate startDate = optionalDate(args, "startDate");
        LocalDate endDate = optionalDate(args, "endDate");

        if (sprintName == null && (startDate == null || endDate == null)) {
            throw new IllegalArgumentException(
                    "Either 'sprintName' or both 'startDate' and 'endDate' must be provided");
        }

        return new QueryParams(startDate, endDate, sprintName, project);
    }

    private static LocalDate optionalDate(JsonObject args, String field) {
        String value = optionalString(args, field);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Invalid date format for " + field + ": " + value + " (expected yyyy-MM-dd)");
        }
    }

    private static String optionalString(JsonObject args, String field) {
        if (!args.has(field) || args.get(field).isJsonNull()) {
            return null;
        }
        String value = args.get(field).getAsString();
        return value.isBlank() ? null : value;
    }

    private static JsonObject buildDateRangeSchema() {
        var sprintName = new JsonObject();
        sprintName.addProperty("type", "string");
        sprintName.addProperty("description",
                "Sprint name (e.g. 'Work Cycle 2'). Preferred — use this instead of date range. Dates are resolved automatically from the sprint.");

        var startDate = new JsonObject();
        startDate.addProperty("type", "string");
        startDate.addProperty("description", "Start date in yyyy-MM-dd format. Only needed if sprintName is not provided.");

        var endDate = new JsonObject();
        endDate.addProperty("type", "string");
        endDate.addProperty("description", "End date in yyyy-MM-dd format. Only needed if sprintName is not provided.");

        var project = new JsonObject();
        project.addProperty("type", "string");
        project.addProperty("description", "YouTrack project to filter by. Optional — defaults to projects derived from configured boards (YOUTRACK_BOARDS)");

        var properties = new JsonObject();
        properties.add("sprintName", sprintName);
        properties.add("startDate", startDate);
        properties.add("endDate", endDate);
        properties.add("project", project);

        var schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        return schema;
    }
}
