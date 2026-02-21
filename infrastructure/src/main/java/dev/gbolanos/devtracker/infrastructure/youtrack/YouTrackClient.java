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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class YouTrackClient {

    private static final Logger log = LoggerFactory.getLogger(YouTrackClient.class);
    private static final int PAGE_SIZE = 100;
    private static final Gson GSON = new Gson();

    private static final Set<String> FORWARD_STATES = Set.of(
            "Pending Review", "Pending QA", "Merged & Closed"
    );
    private static final Set<String> EARLY_STATES = Set.of(
            "Open", "Dev Ready", "In Progress"
    );

    private static final String ISSUE_FIELDS =
            "idReadable,summary,customFields(name,value(name,value,$type))";
    private static final String SPRINT_ISSUE_FIELDS =
            "idReadable,summary,customFields(name,value(name,value,login,fullName,$type))";
    private static final String ACTIVITY_FIELDS =
            "timestamp,field(name),added(name,$type),removed(name,$type)";
    private static final String SPRINT_FIELDS = "id,name,start,finish,archived";

    private final HttpClient http;
    private final String baseUrl;
    private final String token;
    private final String projectId;
    private final String spField;
    private final String sprintField;

    public YouTrackClient() {
        this.http = HttpClient.newHttpClient();
        this.baseUrl = trimTrailingSlash(System.getenv("YOUTRACK_URL"));
        this.token = System.getenv("YOUTRACK_TOKEN");
        this.projectId = System.getenv("YOUTRACK_PROJECT_ID");
        this.spField = Optional.ofNullable(System.getenv("YOUTRACK_SP_FIELD"))
                .orElse("Story Points");
        this.sprintField = Optional.ofNullable(System.getenv("YOUTRACK_SPRINT_FIELD"))
                .orElse("Sprint");

        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("YOUTRACK_URL is not set — YouTrack calls will fail");
        }
        if (token == null || token.isBlank()) {
            log.warn("YOUTRACK_TOKEN is not set — YouTrack calls will fail");
        }
        if (projectId == null || projectId.isBlank()) {
            log.warn("YOUTRACK_PROJECT_ID is not set — YouTrack calls will fail");
        }
    }

    // -- Public API ----------------------------------------------------------

    public List<YouTrackIssue> fetchIssues(LocalDate startDate, LocalDate endDate) {
        requireConfig();
        String query = "project: {" + projectId + "} updated: " + startDate + " .. " + endDate;

        List<JsonObject> rawIssues = fetchAllPages("/api/issues", query, ISSUE_FIELDS);

        List<YouTrackIssue> result = new ArrayList<>();
        for (JsonObject raw : rawIssues) {
            String id = raw.get("idReadable").getAsString();
            String title = raw.get("summary").getAsString();
            Map<String, JsonElement> cfMap = buildCustomFieldMap(raw);

            TicketState state = parseStateField(cfMap);
            if (state == null) {
                log.warn("Skipping issue {} — unrecognized state", id);
                continue;
            }
            TicketType type = parseTypeField(cfMap);
            int storyPoints = parseIntField(cfMap, spField);
            String assignee = parseAssignee(cfMap);

            List<JsonObject> activities = fetchIssueActivities(id);
            CycleOrigin origin = determineCycleOrigin(activities, startDate);
            boolean kickbacked = detectKickback(activities);

            String url = baseUrl + "/issue/" + id;
            result.add(new YouTrackIssue(id, title, state, type, storyPoints,
                    origin, kickbacked, assignee, url));
        }
        log.info("Fetched and enriched {} issues from YouTrack", result.size());
        return result;
    }

    public List<YouTrackIssue> fetchSprintIssues(String boardId, String sprintId) {
        requireConfig();
        requireBoardId(boardId);
        String fields = "id,name,start,finish,issues(" + SPRINT_ISSUE_FIELDS + ")";
        String url = baseUrl + "/api/agiles/" + boardId + "/sprints/" + sprintId
                + "?fields=" + encode(fields);

        log.info("Fetching sprint issues for board={}, sprint={}", boardId, sprintId);
        JsonObject sprintObj = get(url).getAsJsonObject();

        JsonArray issuesArr = sprintObj.has("issues")
                ? sprintObj.getAsJsonArray("issues")
                : new JsonArray();

        List<YouTrackIssue> result = new ArrayList<>();
        for (JsonElement el : issuesArr) {
            JsonObject raw = el.getAsJsonObject();
            String id = raw.get("idReadable").getAsString();
            String title = raw.get("summary").getAsString();
            Map<String, JsonElement> cfMap = buildCustomFieldMap(raw);

            TicketState state = parseStateField(cfMap);
            TicketType type = parseTypeField(cfMap);
            int sp = parseIntField(cfMap, spField);
            String assignee = parseAssignee(cfMap);

            String issueUrl = baseUrl + "/issue/" + id;
            result.add(new YouTrackIssue(id, title,
                    state != null ? state : TicketState.OPEN,
                    type, sp, CycleOrigin.NEW, false, assignee, issueUrl));
        }
        log.info("Fetched {} issues from sprint {}", result.size(), sprintId);
        return result;
    }

    public String findSprintIdByName(String boardId, String sprintName) {
        requireConfig();
        requireBoardId(boardId);
        String url = baseUrl + "/api/agiles/" + boardId + "/sprints"
                + "?fields=" + encode(SPRINT_FIELDS)
                + "&$top=50";

        log.info("Looking up sprint '{}' on board {}", sprintName, boardId);
        JsonArray sprints = get(url).getAsJsonArray();

        for (JsonElement el : sprints) {
            JsonObject sprint = el.getAsJsonObject();
            if (sprintName.equals(sprint.get("name").getAsString())) {
                return sprint.get("id").getAsString();
            }
        }
        throw new IllegalArgumentException(
                "Sprint '" + sprintName + "' not found on board " + boardId);
    }

    // -- HTTP ----------------------------------------------------------------

    private JsonElement get(String url) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException(
                        "YouTrack API error " + resp.statusCode() + " for " + url + ": " + resp.body());
            }
            return GSON.fromJson(resp.body(), JsonElement.class);
        } catch (IOException e) {
            throw new RuntimeException("YouTrack HTTP call failed: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("YouTrack HTTP call interrupted: " + url, e);
        }
    }

    private List<JsonObject> fetchAllPages(String path, String query, String fields) {
        List<JsonObject> all = new ArrayList<>();
        int skip = 0;

        while (true) {
            String url = baseUrl + path
                    + "?query=" + encode(query)
                    + "&fields=" + encode(fields)
                    + "&$top=" + PAGE_SIZE
                    + "&$skip=" + skip;

            log.debug("GET {}", url);
            JsonArray page = get(url).getAsJsonArray();

            for (JsonElement el : page) {
                all.add(el.getAsJsonObject());
            }

            if (page.size() < PAGE_SIZE) {
                break;
            }
            skip += PAGE_SIZE;
        }
        return all;
    }

    // -- Activities ----------------------------------------------------------

    private List<JsonObject> fetchIssueActivities(String issueId) {
        String url = baseUrl + "/api/issues/" + issueId + "/activities"
                + "?categories=CustomFieldCategory"
                + "&fields=" + encode(ACTIVITY_FIELDS)
                + "&$top=200";

        log.debug("Fetching activities for issue {}", issueId);
        JsonArray arr = get(url).getAsJsonArray();

        List<JsonObject> activities = new ArrayList<>();
        for (JsonElement el : arr) {
            activities.add(el.getAsJsonObject());
        }
        activities.sort((a, b) -> Long.compare(
                a.get("timestamp").getAsLong(),
                b.get("timestamp").getAsLong()));
        return activities;
    }

    private CycleOrigin determineCycleOrigin(List<JsonObject> activities, LocalDate cycleStart) {
        for (JsonObject activity : activities) {
            if (!isFieldChange(activity, sprintField)) {
                continue;
            }
            JsonArray added = activity.has("added") ? activity.getAsJsonArray("added") : null;
            if (added == null || added.isEmpty()) {
                continue;
            }
            LocalDate changeDate = toLocalDate(activity.get("timestamp").getAsLong());
            return changeDate.isBefore(cycleStart) ? CycleOrigin.ROLLED_OVER : CycleOrigin.NEW;
        }
        return CycleOrigin.NEW;
    }

    private boolean detectKickback(List<JsonObject> activities) {
        for (JsonObject activity : activities) {
            if (!isFieldChange(activity, "State")) {
                continue;
            }
            JsonArray removed = activity.has("removed")
                    ? activity.getAsJsonArray("removed") : new JsonArray();
            JsonArray added = activity.has("added")
                    ? activity.getAsJsonArray("added") : new JsonArray();

            if (removed.isEmpty() || added.isEmpty()) {
                continue;
            }

            String fromState = removed.get(0).getAsJsonObject().get("name").getAsString();
            String toState = added.get(0).getAsJsonObject().get("name").getAsString();

            if (FORWARD_STATES.contains(fromState) && EARLY_STATES.contains(toState)) {
                log.debug("Kickback detected: {} → {}", fromState, toState);
                return true;
            }
        }
        return false;
    }

    private boolean isFieldChange(JsonObject activity, String fieldName) {
        if (!activity.has("field") || activity.get("field").isJsonNull()) {
            return false;
        }
        JsonObject field = activity.getAsJsonObject("field");
        return field.has("name") && fieldName.equals(field.get("name").getAsString());
    }

    // -- Custom field parsing ------------------------------------------------

    private Map<String, JsonElement> buildCustomFieldMap(JsonObject issue) {
        Map<String, JsonElement> map = new HashMap<>();
        if (!issue.has("customFields")) {
            return map;
        }
        JsonArray fields = issue.getAsJsonArray("customFields");
        for (JsonElement el : fields) {
            JsonObject cf = el.getAsJsonObject();
            String name = cf.get("name").getAsString();
            JsonElement value = cf.has("value") ? cf.get("value") : JsonNull.INSTANCE;
            map.put(name, value);
        }
        return map;
    }

    private TicketState parseStateField(Map<String, JsonElement> cfMap) {
        JsonElement val = cfMap.get("State");
        if (val == null || val.isJsonNull() || !val.isJsonObject()) {
            return null;
        }
        String stateName = val.getAsJsonObject().get("name").getAsString();
        try {
            return TicketState.fromLabel(stateName);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown YouTrack state label: '{}' — defaulting to OPEN", stateName);
            return TicketState.OPEN;
        }
    }

    private TicketType parseTypeField(Map<String, JsonElement> cfMap) {
        JsonElement val = cfMap.get("Type");
        if (val == null || val.isJsonNull() || !val.isJsonObject()) {
            return TicketType.TASK;
        }
        String typeName = val.getAsJsonObject().get("name").getAsString();
        try {
            return TicketType.fromLabel(typeName);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown YouTrack type label: '{}' — defaulting to TASK", typeName);
            return TicketType.TASK;
        }
    }

    private int parseIntField(Map<String, JsonElement> cfMap, String fieldName) {
        JsonElement val = cfMap.get(fieldName);
        if (val == null || val.isJsonNull()) {
            return 0;
        }
        if (val.isJsonPrimitive()) {
            try {
                return val.getAsInt();
            } catch (NumberFormatException e) {
                log.warn("Could not parse '{}' as int for field '{}'", val, fieldName);
                return 0;
            }
        }
        if (val.isJsonObject() && val.getAsJsonObject().has("value")) {
            JsonElement inner = val.getAsJsonObject().get("value");
            if (inner.isJsonPrimitive()) {
                return inner.getAsInt();
            }
        }
        return 0;
    }

    private String parseAssignee(Map<String, JsonElement> cfMap) {
        JsonElement val = cfMap.get("Assignee");
        if (val == null || val.isJsonNull() || !val.isJsonObject()) {
            return null;
        }
        JsonObject user = val.getAsJsonObject();
        if (user.has("fullName") && !user.get("fullName").isJsonNull()) {
            return user.get("fullName").getAsString();
        }
        if (user.has("login")) {
            return user.get("login").getAsString();
        }
        return null;
    }

    // -- Utilities -----------------------------------------------------------

    private void requireConfig() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("YOUTRACK_URL is not configured");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("YOUTRACK_TOKEN is not configured");
        }
    }

    private void requireBoardId(String boardId) {
        if (boardId == null || boardId.isBlank()) {
            throw new IllegalStateException(
                    "boardId is required — set YOUTRACK_BOARD_ID or pass it explicitly");
        }
    }

    private static LocalDate toLocalDate(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
