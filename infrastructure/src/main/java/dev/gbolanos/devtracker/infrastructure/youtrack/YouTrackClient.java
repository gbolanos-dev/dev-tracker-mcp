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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
            "idReadable,summary,customFields(name,value(name,value,login,fullName,$type))";
    private static final String SPRINT_ISSUE_FIELDS =
            "idReadable,summary,customFields(name,value(name,value,login,fullName,$type))";
    private static final String ACTIVITY_FIELDS =
            "timestamp,field(name),added(name,$type),removed(name,$type)";
    private static final String SPRINT_FIELDS = "id,name,start,finish,archived";

    private record BoardInfo(String id, String name, List<String> projectShortNames) {}

    private final HttpClient http;
    private final String baseUrl;
    private final String token;
    private final List<String> boardNames;
    private final String legacyBoardName;
    private final String spField;
    private final String sprintField;
    private final String assigneeField;
    private final String primaryDevField;

    // Cached board resolution (lazy-loaded)
    private List<BoardInfo> resolvedBoards;
    private BoardInfo resolvedLegacyBoard;
    private List<String> derivedProjects;

    // Cached current user info (lazy-loaded)
    private String currentUserLogin;
    private String currentUserFullName;

    public YouTrackClient() {
        this.http = HttpClient.newHttpClient();
        this.baseUrl = trimTrailingSlash(System.getenv("YOUTRACK_URL"));
        this.token = System.getenv("YOUTRACK_TOKEN");
        this.boardNames = parseBoardNames(System.getenv("YOUTRACK_BOARDS"));
        this.legacyBoardName = Optional.ofNullable(System.getenv("YOUTRACK_LEGACY_BOARD"))
                .filter(s -> !s.isBlank()).orElse(null);
        this.spField = Optional.ofNullable(System.getenv("YOUTRACK_SP_FIELD"))
                .orElse("Story Points");
        this.sprintField = Optional.ofNullable(System.getenv("YOUTRACK_SPRINT_FIELD"))
                .orElse("Sprint");
        this.assigneeField = Optional.ofNullable(System.getenv("YOUTRACK_ASSIGNEE_FIELD"))
                .orElse("Assignee");
        this.primaryDevField = Optional.ofNullable(System.getenv("YOUTRACK_PRIMARY_DEV_FIELD"))
                .orElse("Primary Dev");

        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("YOUTRACK_URL is not set — YouTrack calls will fail");
        }
        if (token == null || token.isBlank()) {
            log.warn("YOUTRACK_TOKEN is not set — YouTrack calls will fail");
        }
        if (boardNames.isEmpty() && legacyBoardName == null) {
            log.debug("YOUTRACK_BOARDS is not set — project scope will not be derived from boards");
        }
    }

    private static List<String> parseBoardNames(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    // -- Public API ----------------------------------------------------------

    public List<YouTrackIssue> fetchIssues(LocalDate startDate, LocalDate endDate, String project) {
        requireConfig();
        ensureBoardsResolved();

        StringBuilder query = new StringBuilder();
        query.append("(").append(queryFieldRef(assigneeField)).append(": me");
        query.append(" OR ").append(queryFieldRef(primaryDevField)).append(": me)");
        query.append(" updated: ").append(startDate).append(" .. ").append(endDate);
        query.append(buildProjectClause(project));

        List<JsonObject> rawIssues = fetchAllPages("/api/issues", query.toString(), ISSUE_FIELDS);
        return enrichRawIssues(rawIssues, startDate);
    }

    public List<YouTrackIssue> fetchIssuesBySprint(String sprintName, String project, LocalDate cycleStart) {
        requireConfig();
        ensureBoardsResolved();

        StringBuilder query = new StringBuilder();
        query.append(queryFieldRef(sprintField)).append(": {").append(sprintName).append("}");
        query.append(" (").append(queryFieldRef(assigneeField)).append(": me");
        query.append(" OR ").append(queryFieldRef(primaryDevField)).append(": me)");
        query.append(buildProjectClause(project));

        log.info("Querying issues by sprint field: {}", query);
        List<JsonObject> rawIssues = fetchAllPages("/api/issues", query.toString(), ISSUE_FIELDS);
        return enrichRawIssues(rawIssues, cycleStart);
    }

    public SprintDateRange resolveSprintDates(String sprintName) {
        SprintDateRange result = tryResolveSprintDates(sprintName);
        if (result != null) {
            return result;
        }
        throw new IllegalArgumentException(
                "Sprint '" + sprintName + "' not found on any board");
    }

    public SprintDateRange tryResolveSprintDates(String sprintName) {
        requireConfig();
        ensureBoardsResolved();

        // Try resolved boards first
        if (resolvedBoards != null) {
            for (BoardInfo board : resolvedBoards) {
                SprintDateRange result = tryResolveSprintDatesOnBoard(board.id(), sprintName);
                if (result != null) {
                    log.info("Found sprint '{}' on board '{}'", sprintName, board.name());
                    return result;
                }
            }
        }

        // Try legacy board
        if (resolvedLegacyBoard != null) {
            log.info("Sprint '{}' not found on primary boards, trying legacy board '{}'",
                    sprintName, resolvedLegacyBoard.name());
            SprintDateRange result = tryResolveSprintDatesOnBoard(resolvedLegacyBoard.id(), sprintName);
            if (result != null) {
                return result;
            }
        }

        // Fallback: search all boards
        log.info("Sprint '{}' not found on configured boards, searching all boards", sprintName);
        return searchAllBoardsForSprint(sprintName);
    }

    private SprintDateRange searchAllBoardsForSprint(String sprintName) {
        String url = baseUrl + "/api/agiles?fields=" + encode("id,name") + "&$top=100";
        log.debug("Listing all agile boards");
        JsonArray boards = get(url).getAsJsonArray();

        for (JsonElement el : boards) {
            JsonObject board = el.getAsJsonObject();
            String id = board.get("id").getAsString();
            String name = board.has("name") && !board.get("name").isJsonNull()
                    ? board.get("name").getAsString() : id;

            try {
                SprintDateRange result = tryResolveSprintDatesOnBoard(id, sprintName);
                if (result != null) {
                    log.info("Found sprint '{}' on board '{}' ({})", sprintName, name, id);
                    return result;
                }
            } catch (RuntimeException e) {
                log.warn("Skipping board '{}' ({}) — {}", name, id, e.getMessage());
            }
        }
        return null;
    }

    private SprintDateRange tryResolveSprintDatesOnBoard(String boardId, String sprintName) {
        String url = baseUrl + "/api/agiles/" + boardId + "/sprints"
                + "?fields=" + encode(SPRINT_FIELDS)
                + "&$top=200";

        log.info("Looking up sprint '{}' on board {} for date resolution", sprintName, boardId);
        JsonArray sprints = get(url).getAsJsonArray();

        for (JsonElement el : sprints) {
            JsonObject sprint = el.getAsJsonObject();
            if (sprintName.equals(sprint.get("name").getAsString())) {
                LocalDate start = sprint.has("start") && !sprint.get("start").isJsonNull()
                        ? toLocalDate(sprint.get("start").getAsLong()) : null;
                LocalDate end = sprint.has("finish") && !sprint.get("finish").isJsonNull()
                        ? toLocalDate(sprint.get("finish").getAsLong()) : null;
                log.info("Resolved sprint '{}' → {} to {}", sprintName, start, end);
                return new SprintDateRange(start, end);
            }
        }
        return null;
    }

    public SprintResult fetchSprintWithDates(String boardId, String sprintName, String project) {
        SprintDateRange dates = tryResolveSprintDates(sprintName);
        LocalDate startDate = dates != null ? dates.startDate() : null;
        LocalDate endDate = dates != null ? dates.endDate() : null;
        if (dates == null) {
            log.warn("Could not resolve dates for sprint '{}' — tickets will still be fetched by sprint field", sprintName);
        }
        List<YouTrackIssue> issues = fetchIssuesBySprint(sprintName, project, startDate);
        return new SprintResult(issues, startDate, endDate);
    }

    public List<YouTrackIssue> fetchSprintIssues(String boardId, String sprintId) {
        requireConfig();
        String effectiveBoardId = resolveBoardId(boardId);
        String fields = "id,name,start,finish,issues(" + SPRINT_ISSUE_FIELDS + ")";
        String url = baseUrl + "/api/agiles/" + effectiveBoardId + "/sprints/" + sprintId
                + "?fields=" + encode(fields);

        log.info("Fetching sprint issues for board={}, sprint={}", effectiveBoardId, sprintId);
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
            String assignee = parseUserField(cfMap, assigneeField);
            String primaryDev = parseUserField(cfMap, primaryDevField);

            String issueUrl = baseUrl + "/issue/" + id;
            result.add(new YouTrackIssue(id, title,
                    state != null ? state : TicketState.OPEN,
                    type, sp, CycleOrigin.NEW, false, assignee, primaryDev, issueUrl));
        }
        log.info("Fetched {} issues from sprint {}", result.size(), sprintId);
        return result;
    }

    public String findSprintIdByName(String boardId, String sprintName) {
        requireConfig();
        String effectiveBoardId = resolveBoardId(boardId);
        String url = baseUrl + "/api/agiles/" + effectiveBoardId + "/sprints"
                + "?fields=" + encode(SPRINT_FIELDS)
                + "&$top=200";

        log.info("Looking up sprint '{}' on board {}", sprintName, effectiveBoardId);
        JsonArray sprints = get(url).getAsJsonArray();

        for (JsonElement el : sprints) {
            JsonObject sprint = el.getAsJsonObject();
            if (sprintName.equals(sprint.get("name").getAsString())) {
                return sprint.get("id").getAsString();
            }
        }
        throw new IllegalArgumentException(
                "Sprint '" + sprintName + "' not found on board " + effectiveBoardId);
    }

    public String resolveBoardId(String boardId) {
        if (boardId != null && !boardId.isBlank()) {
            return boardId;
        }
        ensureBoardsResolved();
        if (resolvedBoards != null && !resolvedBoards.isEmpty()) {
            return resolvedBoards.get(0).id();
        }
        if (resolvedLegacyBoard != null) {
            return resolvedLegacyBoard.id();
        }
        throw new IllegalStateException(
                "No board available — set YOUTRACK_BOARDS or pass boardId explicitly");
    }

    // -- Current user --------------------------------------------------------

    private void ensureCurrentUser() {
        if (currentUserLogin != null) {
            return;
        }
        String url = baseUrl + "/api/users/me?fields=" + encode("login,fullName");
        log.info("Fetching current YouTrack user");
        JsonObject user = get(url).getAsJsonObject();
        currentUserLogin = user.has("login") ? user.get("login").getAsString() : null;
        currentUserFullName = user.has("fullName") && !user.get("fullName").isJsonNull()
                ? user.get("fullName").getAsString() : null;
        log.info("Current YouTrack user: login={}, fullName={}", currentUserLogin, currentUserFullName);
    }

    private boolean isCurrentUser(String value) {
        if (value == null) {
            return false;
        }
        return value.equalsIgnoreCase(currentUserLogin)
                || value.equalsIgnoreCase(currentUserFullName);
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

    private String parseUserField(Map<String, JsonElement> cfMap, String fieldName) {
        JsonElement val = cfMap.get(fieldName);
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

    // -- Enrichment ----------------------------------------------------------

    private List<YouTrackIssue> enrichRawIssues(List<JsonObject> rawIssues, LocalDate cycleStart) {
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
            String assignee = parseUserField(cfMap, assigneeField);
            String primaryDev = parseUserField(cfMap, primaryDevField);

            CycleOrigin origin = CycleOrigin.NEW;
            boolean kickbacked = false;
            if (cycleStart != null) {
                List<JsonObject> activities = fetchIssueActivities(id);
                origin = determineCycleOrigin(activities, cycleStart);
                kickbacked = detectKickback(activities);
            }

            String url = baseUrl + "/issue/" + id;
            result.add(new YouTrackIssue(id, title, state, type, storyPoints,
                    origin, kickbacked, assignee, primaryDev, url));
        }
        log.info("Fetched and enriched {} issues from YouTrack", result.size());
        return result;
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

    private String buildProjectClause(String project) {
        if (project != null && !project.isBlank()) {
            return " project: {" + project + "}";
        }
        if (derivedProjects != null && !derivedProjects.isEmpty()) {
            return " project: " + derivedProjects.stream()
                    .map(p -> "{" + p + "}")
                    .collect(Collectors.joining(", "));
        }
        return "";
    }

    private void ensureBoardsResolved() {
        if (resolvedBoards != null || (boardNames.isEmpty() && legacyBoardName == null)) {
            return;
        }

        String url = baseUrl + "/api/agiles?fields="
                + encode("id,name,projects(id,name,shortName)") + "&$top=100";
        log.info("Fetching agile boards for auto-discovery");
        JsonArray allBoards = get(url).getAsJsonArray();

        Map<String, BoardInfo> boardsByName = new HashMap<>();
        for (JsonElement el : allBoards) {
            JsonObject board = el.getAsJsonObject();
            String id = board.get("id").getAsString();
            String name = board.has("name") && !board.get("name").isJsonNull()
                    ? board.get("name").getAsString() : id;

            List<String> projectShortNames = new ArrayList<>();
            if (board.has("projects") && !board.get("projects").isJsonNull()) {
                for (JsonElement projEl : board.getAsJsonArray("projects")) {
                    JsonObject proj = projEl.getAsJsonObject();
                    if (proj.has("shortName") && !proj.get("shortName").isJsonNull()) {
                        projectShortNames.add(proj.get("shortName").getAsString());
                    }
                }
            }
            boardsByName.put(name, new BoardInfo(id, name, projectShortNames));
        }

        // Resolve configured boards
        resolvedBoards = new ArrayList<>();
        for (String name : boardNames) {
            BoardInfo info = boardsByName.get(name);
            if (info != null) {
                resolvedBoards.add(info);
                log.info("Resolved board '{}' → id={}, projects={}",
                        name, info.id(), info.projectShortNames());
            } else {
                log.warn("Configured board '{}' not found in YouTrack", name);
            }
        }

        // Resolve legacy board
        if (legacyBoardName != null) {
            resolvedLegacyBoard = boardsByName.get(legacyBoardName);
            if (resolvedLegacyBoard != null) {
                log.info("Resolved legacy board '{}' → id={}, projects={}",
                        legacyBoardName, resolvedLegacyBoard.id(),
                        resolvedLegacyBoard.projectShortNames());
            } else {
                log.warn("Configured legacy board '{}' not found in YouTrack", legacyBoardName);
            }
        }

        // Derive projects from all resolved boards
        Set<String> projects = new LinkedHashSet<>();
        for (BoardInfo board : resolvedBoards) {
            projects.addAll(board.projectShortNames());
        }
        if (resolvedLegacyBoard != null) {
            projects.addAll(resolvedLegacyBoard.projectShortNames());
        }
        derivedProjects = new ArrayList<>(projects);

        if (!derivedProjects.isEmpty()) {
            log.info("Derived projects from boards: {}", derivedProjects);
        }
    }

    private static LocalDate toLocalDate(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String queryFieldRef(String fieldName) {
        return fieldName.contains(" ") ? "#{" + fieldName + "}" : fieldName;
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
