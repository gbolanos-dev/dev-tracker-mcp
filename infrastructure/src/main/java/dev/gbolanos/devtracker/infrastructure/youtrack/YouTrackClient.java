package dev.gbolanos.devtracker.infrastructure.youtrack;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.gbolanos.devtracker.domain.enums.CycleOrigin;
import dev.gbolanos.devtracker.domain.enums.TicketState;
import dev.gbolanos.devtracker.domain.enums.TicketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class YouTrackClient {

    private static final Logger log = LoggerFactory.getLogger(YouTrackClient.class);
    private static final Gson GSON = new Gson();
    private static final int PAGE_SIZE = 100;

    private static final String ISSUE_FIELDS =
            "idReadable,summary,customFields(name,value(name,value,login,fullName,$type))";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String token;
    private final String spField;
    private final String sprintField;
    private final String assigneeField;
    private final String primaryDevField;

    public YouTrackClient() {
        this.httpClient = HttpClient.newHttpClient();

        String url = System.getenv("YOUTRACK_URL");
        if (url == null || url.isBlank()) {
            log.warn("YOUTRACK_URL is not set — YouTrack calls will fail at runtime");
            this.baseUrl = "";
        } else {
            this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }

        this.token = System.getenv("YOUTRACK_TOKEN");
        if (this.token == null || this.token.isBlank()) {
            log.warn("YOUTRACK_TOKEN is not set — YouTrack calls will fail at runtime");
        }

        this.spField = Optional.ofNullable(System.getenv("YOUTRACK_SP_FIELD"))
                .filter(s -> !s.isBlank()).orElse("Story Points");
        this.sprintField = Optional.ofNullable(System.getenv("YOUTRACK_SPRINT_FIELD"))
                .filter(s -> !s.isBlank()).orElse("Sprint");
        this.assigneeField = Optional.ofNullable(System.getenv("YOUTRACK_ASSIGNEE_FIELD"))
                .filter(s -> !s.isBlank()).orElse("Assignee");
        this.primaryDevField = Optional.ofNullable(System.getenv("YOUTRACK_PRIMARY_DEV_FIELD"))
                .filter(s -> !s.isBlank()).orElse("Primary Dev");
    }

    // ── HTTP helper ──────────────────────────────────────────────────────

    private JsonElement get(String path) {
        if (token == null || token.isBlank()) {
            throw new RuntimeException("YOUTRACK_TOKEN is not configured");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("YouTrack API error: status={} body={}", response.statusCode(), response.body());
                throw new RuntimeException(
                        "YouTrack API returned " + response.statusCode() + ": " + response.body());
            }

            return GSON.fromJson(response.body(), JsonElement.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("YouTrack API call failed: " + e.getMessage(), e);
        }
    }

    // ── Core query ───────────────────────────────────────────────────────

    public List<YouTrackIssue> fetchIssuesBySprint(String sprintName) {
        String query = String.format("#{%s}: {%s} (#{%s}: me OR #{%s}: me)",
                sprintField, sprintName, assigneeField, primaryDevField);
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

        List<YouTrackIssue> issues = new ArrayList<>();
        int skip = 0;

        while (true) {
            String path = String.format("/api/issues?query=%s&fields=%s&$top=%d&$skip=%d",
                    encodedQuery, URLEncoder.encode(ISSUE_FIELDS, StandardCharsets.UTF_8),
                    PAGE_SIZE, skip);

            JsonArray page = get(path).getAsJsonArray();

            for (JsonElement element : page) {
                issues.add(parseIssue(element.getAsJsonObject()));
            }

            if (page.size() < PAGE_SIZE) {
                break;
            }
            skip += PAGE_SIZE;
        }

        log.info("Fetched {} issues for sprint '{}'", issues.size(), sprintName);
        return issues;
    }

    // ── Application-layer entry points (stubs for deferred features) ────

    /**
     * Fetches sprint issues and resolves sprint dates.
     * Date resolution is not yet implemented — dates will be null.
     */
    public SprintResult fetchSprintWithDates(String boardId, String sprintName, String project) {
        List<YouTrackIssue> issues = fetchIssuesBySprint(sprintName);
        // TODO: resolve sprint start/end dates via board API
        return new SprintResult(issues, null, null);
    }

    /**
     * Fetches issues by date range.
     * Not yet implemented — will be added in a subsequent pass.
     */
    public List<YouTrackIssue> fetchIssues(LocalDate startDate, LocalDate endDate, String project) {
        throw new UnsupportedOperationException(
                "Date-range issue fetch not yet implemented. Use sprintName parameter instead.");
    }

    /**
     * Resolves sprint name to start/end dates.
     * Not yet implemented — will be added in a subsequent pass.
     */
    public SprintDateRange resolveSprintDates(String sprintName) {
        throw new UnsupportedOperationException(
                "Sprint date resolution not yet implemented. Use explicit startDate/endDate parameters instead.");
    }

    // ── JSON → YouTrackIssue mapping ─────────────────────────────────────

    private YouTrackIssue parseIssue(JsonObject json) {
        String id = json.get("idReadable").getAsString();
        String title = json.get("summary").getAsString();

        TicketState state = TicketState.OPEN;
        TicketType type = TicketType.TASK;
        int storyPoints = 0;
        String assignee = null;
        String primaryDev = null;

        JsonArray customFields = json.getAsJsonArray("customFields");
        if (customFields != null) {
            for (JsonElement fieldEl : customFields) {
                JsonObject field = fieldEl.getAsJsonObject();
                String fieldName = field.get("name").getAsString();
                JsonElement valueEl = field.get("value");

                if (valueEl == null || valueEl instanceof JsonNull) {
                    continue;
                }

                if ("State".equals(fieldName)) {
                    state = parseState(valueEl);
                } else if ("Type".equals(fieldName)) {
                    type = parseType(valueEl);
                } else if (spField.equals(fieldName)) {
                    storyPoints = parseStoryPoints(valueEl);
                } else if (assigneeField.equals(fieldName)) {
                    assignee = parseUserName(valueEl);
                } else if (primaryDevField.equals(fieldName)) {
                    primaryDev = parseUserName(valueEl);
                }
            }
        }

        String youtrackUrl = baseUrl + "/issue/" + id;

        return new YouTrackIssue(
                id, title, state, type, storyPoints,
                CycleOrigin.NEW, false,
                assignee, primaryDev, youtrackUrl
        );
    }

    private TicketState parseState(JsonElement valueEl) {
        try {
            String name = valueEl.getAsJsonObject().get("name").getAsString();
            return TicketState.fromLabel(name);
        } catch (Exception e) {
            log.debug("Unknown ticket state, defaulting to OPEN: {}", e.getMessage());
            return TicketState.OPEN;
        }
    }

    private TicketType parseType(JsonElement valueEl) {
        try {
            String name = valueEl.getAsJsonObject().get("name").getAsString();
            return TicketType.fromLabel(name);
        } catch (Exception e) {
            log.debug("Unknown ticket type, defaulting to TASK: {}", e.getMessage());
            return TicketType.TASK;
        }
    }

    private int parseStoryPoints(JsonElement valueEl) {
        try {
            return valueEl.getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private String parseUserName(JsonElement valueEl) {
        try {
            JsonObject obj = valueEl.getAsJsonObject();
            if (obj.has("fullName") && !obj.get("fullName").isJsonNull()) {
                return obj.get("fullName").getAsString();
            }
            if (obj.has("login") && !obj.get("login").isJsonNull()) {
                return obj.get("login").getAsString();
            }
        } catch (Exception e) {
            log.debug("Could not parse user name: {}", e.getMessage());
        }
        return null;
    }
}
