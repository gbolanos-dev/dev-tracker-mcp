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
        var getCodeReviews = new GetCodeReviews(gitHubClient);

        var registry = new ToolRegistry();
        var dateRangeSchema = buildDateRangeSchema();

        registry.register(new ToolDefinition(
                "get_cycle_metrics",
                "Aggregate metrics for a work cycle (tickets, points, reviews)",
                dateRangeSchema,
                a -> {
                    var start = parseDate(a, "startDate");
                    var end = parseDate(a, "endDate");
                    return getCycleMetrics.execute(start, end);
                }
        ));

        registry.register(new ToolDefinition(
                "get_ticket_details",
                "Detailed ticket list with linked PRs and effort levels",
                dateRangeSchema,
                a -> {
                    var start = parseDate(a, "startDate");
                    var end = parseDate(a, "endDate");
                    return getTicketDetails.execute(start, end);
                }
        ));

        registry.register(new ToolDefinition(
                "get_code_reviews",
                "Code reviews performed by the user in the date range",
                dateRangeSchema,
                a -> {
                    var start = parseDate(a, "startDate");
                    var end = parseDate(a, "endDate");
                    return getCodeReviews.execute(start, end);
                }
        ));

        var handler = new JsonRpcHandler(registry);
        var server = new McpServer(handler);
        server.start();
    }

    private static LocalDate parseDate(JsonObject args, String field) {
        if (!args.has(field) || args.get(field).isJsonNull()) {
            throw new IllegalArgumentException("Missing required parameter: " + field);
        }
        var value = args.get(field).getAsString();
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format for " + field + ": " + value + " (expected yyyy-MM-dd)");
        }
    }

    private static JsonObject buildDateRangeSchema() {
        var startDate = new JsonObject();
        startDate.addProperty("type", "string");
        startDate.addProperty("description", "Start date in yyyy-MM-dd format");

        var endDate = new JsonObject();
        endDate.addProperty("type", "string");
        endDate.addProperty("description", "End date in yyyy-MM-dd format");

        var properties = new JsonObject();
        properties.add("startDate", startDate);
        properties.add("endDate", endDate);

        var required = new JsonArray();
        required.add("startDate");
        required.add("endDate");

        var schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }
}
