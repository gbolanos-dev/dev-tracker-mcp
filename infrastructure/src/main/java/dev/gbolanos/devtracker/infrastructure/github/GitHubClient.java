package dev.gbolanos.devtracker.infrastructure.github;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.gbolanos.devtracker.domain.enums.EffortLevel;
import dev.gbolanos.devtracker.domain.model.CodeReview;
import dev.gbolanos.devtracker.domain.model.PullRequest;
import dev.gbolanos.devtracker.domain.service.EffortCalculator;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);
    private static final String API_BASE = "https://api.github.com";
    private static final int PER_PAGE = 100;
    private static final Gson GSON = new Gson();
    private static final Pattern TICKET_ID_PATTERN = Pattern.compile("([A-Z][A-Z0-9]+-\\d+)");

    private final HttpClient http;
    private final String token;
    private final String username;
    private final String org;

    public GitHubClient() {
        this.http = HttpClient.newHttpClient();
        this.token = System.getenv("GITHUB_TOKEN");
        this.username = System.getenv("GITHUB_USERNAME");
        this.org = System.getenv("GITHUB_ORG");

        if (token == null || token.isBlank()) {
            log.warn("GITHUB_TOKEN is not set — GitHub calls will fail");
        }
        if (username == null || username.isBlank()) {
            log.warn("GITHUB_USERNAME is not set — GitHub calls will fail");
        }
    }

    // -- Public API ----------------------------------------------------------

    public List<PullRequest> fetchMergedPRs(LocalDate startDate, LocalDate endDate) {
        requireConfig();
        String query = buildSearchQuery("author:" + username, startDate, endDate);
        List<JsonObject> searchResults = searchPRs(query);

        List<PullRequest> result = new ArrayList<>();
        for (JsonObject item : searchResults) {
            PullRequest pr = toPullRequest(item);
            if (pr != null) {
                result.add(pr);
            }
        }
        log.info("Fetched {} merged PRs for user {}", result.size(), username);
        return result;
    }

    public List<CodeReview> fetchReviews(LocalDate startDate, LocalDate endDate) {
        requireConfig();
        String query = buildSearchQuery(
                "reviewed-by:" + username + " -author:" + username, startDate, endDate);
        List<JsonObject> searchResults = searchPRs(query);

        List<CodeReview> result = new ArrayList<>();
        for (JsonObject item : searchResults) {
            CodeReview review = toCodeReview(item);
            if (review != null) {
                result.add(review);
            }
        }
        log.info("Fetched {} code reviews for user {}", result.size(), username);
        return result;
    }

    public static String extractTicketId(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = TICKET_ID_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    // -- Search --------------------------------------------------------------

    private String buildSearchQuery(String userFilter, LocalDate startDate, LocalDate endDate) {
        StringBuilder q = new StringBuilder();
        q.append("type:pr ").append(userFilter);
        q.append(" merged:").append(startDate).append("..").append(endDate);
        if (org != null && !org.isBlank()) {
            q.append(" org:").append(org);
        }
        return q.toString();
    }

    private List<JsonObject> searchPRs(String query) {
        List<JsonObject> all = new ArrayList<>();
        int page = 1;

        while (true) {
            String url = API_BASE + "/search/issues"
                    + "?q=" + encode(query)
                    + "&per_page=" + PER_PAGE
                    + "&page=" + page;

            log.debug("GitHub search: {}", url);
            JsonObject resp = get(url).getAsJsonObject();
            JsonArray items = resp.getAsJsonArray("items");

            for (JsonElement el : items) {
                all.add(el.getAsJsonObject());
            }

            if (items.size() < PER_PAGE) {
                break;
            }
            page++;
        }
        return all;
    }

    // -- PR details ----------------------------------------------------------

    private JsonObject fetchPRDetails(String owner, String repo, int number) {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/pulls/" + number;
        log.debug("Fetching PR details: {}/{} #{}", owner, repo, number);
        return get(url).getAsJsonObject();
    }

    private LocalDate fetchReviewDate(String owner, String repo, int number) {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/pulls/" + number + "/reviews";
        log.debug("Fetching reviews for {}/{} #{}", owner, repo, number);
        JsonArray reviews = get(url).getAsJsonArray();

        LocalDate latest = null;
        for (JsonElement el : reviews) {
            JsonObject review = el.getAsJsonObject();
            String reviewer = review.getAsJsonObject("user").get("login").getAsString();
            if (!username.equalsIgnoreCase(reviewer)) {
                continue;
            }
            String submittedAt = review.get("submitted_at").getAsString();
            LocalDate date = LocalDate.parse(submittedAt, DateTimeFormatter.ISO_DATE_TIME);
            if (latest == null || date.isAfter(latest)) {
                latest = date;
            }
        }
        return latest;
    }

    // -- Mapping -------------------------------------------------------------

    private PullRequest toPullRequest(JsonObject searchItem) {
        int number = searchItem.get("number").getAsInt();
        String htmlUrl = searchItem.get("html_url").getAsString();
        String title = searchItem.get("title").getAsString();
        String author = searchItem.getAsJsonObject("user").get("login").getAsString();

        RepoInfo repoInfo = extractRepoInfo(searchItem);
        if (repoInfo == null) {
            log.warn("Could not determine repo for PR #{}, skipping", number);
            return null;
        }

        JsonObject details = fetchPRDetails(repoInfo.owner, repoInfo.repo, number);
        int linesAdded = details.get("additions").getAsInt();
        int linesDeleted = details.get("deletions").getAsInt();
        int filesChanged = details.get("changed_files").getAsInt();

        String branch = details.getAsJsonObject("head").get("ref").getAsString();
        String linkedTicketId = extractTicketId(branch);
        if (linkedTicketId == null) {
            linkedTicketId = extractTicketId(title);
        }

        String mergedAtStr = details.get("merged_at").getAsString();
        LocalDate mergedAt = LocalDate.parse(mergedAtStr, DateTimeFormatter.ISO_DATE_TIME);

        return new PullRequest(number, repoInfo.repo, title, author,
                linesAdded, linesDeleted, filesChanged,
                linkedTicketId, mergedAt, htmlUrl);
    }

    private CodeReview toCodeReview(JsonObject searchItem) {
        int number = searchItem.get("number").getAsInt();
        String htmlUrl = searchItem.get("html_url").getAsString();
        String prTitle = searchItem.get("title").getAsString();

        RepoInfo repoInfo = extractRepoInfo(searchItem);
        if (repoInfo == null) {
            log.warn("Could not determine repo for review on PR #{}, skipping", number);
            return null;
        }

        JsonObject details = fetchPRDetails(repoInfo.owner, repoInfo.repo, number);
        int linesReviewed = details.get("additions").getAsInt() + details.get("deletions").getAsInt();
        int filesReviewed = details.get("changed_files").getAsInt();

        EffortLevel effort = EffortCalculator.fromPullRequest(
                details.get("additions").getAsInt(),
                details.get("deletions").getAsInt(),
                filesReviewed);

        LocalDate reviewedAt = fetchReviewDate(repoInfo.owner, repoInfo.repo, number);
        if (reviewedAt == null) {
            String mergedAtStr = details.get("merged_at").getAsString();
            reviewedAt = LocalDate.parse(mergedAtStr, DateTimeFormatter.ISO_DATE_TIME);
        }

        return new CodeReview(number, repoInfo.repo, prTitle, effort,
                null, reviewedAt, htmlUrl, linesReviewed, filesReviewed);
    }

    // -- Repo info extraction ------------------------------------------------

    private RepoInfo extractRepoInfo(JsonObject searchItem) {
        if (!searchItem.has("repository_url")) {
            return null;
        }
        String repoUrl = searchItem.get("repository_url").getAsString();
        // Format: https://api.github.com/repos/{owner}/{repo}
        String[] parts = repoUrl.split("/");
        if (parts.length < 2) {
            return null;
        }
        return new RepoInfo(parts[parts.length - 2], parts[parts.length - 1]);
    }

    private record RepoInfo(String owner, String repo) {}

    // -- HTTP ----------------------------------------------------------------

    private JsonElement get(String url) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException(
                        "GitHub API error " + resp.statusCode() + " for " + url + ": " + resp.body());
            }
            return GSON.fromJson(resp.body(), JsonElement.class);
        } catch (IOException e) {
            throw new RuntimeException("GitHub HTTP call failed: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("GitHub HTTP call interrupted: " + url, e);
        }
    }

    // -- Utilities -----------------------------------------------------------

    private void requireConfig() {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("GITHUB_TOKEN is not configured");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("GITHUB_USERNAME is not configured");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
