# Dev Tracker MCP — Session Handoff

> **Instructions for the next session:** Read this entire file, then execute the plan step by step. Build one component at a time, waiting for approval before moving to the next step. The project directory is `/Users/gbolanos/Documents/Development/Personal/dev-tracker-mcp` (currently empty).

---

## What We're Building

A greenfield MCP server (JSON-RPC 2.0 over stdio) that tracks engineering contributions and scrum master duties across 6-week work cycles. Integrates YouTrack (tickets), GitHub (PRs/reviews), and Google Sheets (reporting). Purpose: build a Senior Engineer promotion case. The user also serves as Scrum Master.

## Tech Stack

- Java 17 (Zulu/Azul vendor)
- Gradle 9.3.1 with Kotlin DSL
- Shadow JAR for packaging (`com.gradleup.shadow:8.3.5`)
- Foojay toolchain resolver (`org.gradle.toolchains.foojay-resolver-convention:0.9.0`)
- `java.net.http.HttpClient` for HTTP calls (no extra HTTP libraries)
- Logback for logging (file-only at `~/.dev-tracker-mcp/logs/server.log`, stdout stays clean for JSON-RPC)
- Google Sheets API v4 Java client library (service account auth — credentials file already available)
- MCP JSON-RPC protocol version: `2024-11-05`

## Decisions Made

1. **PR-to-ticket linking**: Ticket ID (e.g., `PROJ-123`) appears in BOTH branch name prefix AND PR title prefix. Parse from either.
2. **Kick-back handling**: YouTrack has a dedicated "Kickbacked" state/column. If a ticket reaches this state, subtract its points from the completed total.
3. **YouTrack states** (may need adjustment later): `Open`, `Dev Ready`, `In Progress`, `Pending Review`, `Pending QA`, `Merged & Closed`, `Kickbacked`
4. **"Completed" definition**: Points count toward completed if ticket is in `Pending Review`, `Pending QA`, or `Merged & Closed`.
5. **Team scope for scrum master**: All tickets on a specific sprint board (not user-filtered).
6. **Effort level**: Composite signal — story points from YouTrack + lines changed + files changed from GitHub. Highest signal wins.
7. **Google Sheets auth**: Service account (already set up, no OAuth flow needed).

---

## Project Structure

This should be a full Claude Code project with CLAUDE.md, skills, subagents, etc.

```
dev-tracker-mcp/
├── .claude/
│   ├── settings.json
│   ├── skills/
│   │   ├── build/SKILL.md          # /build — build, test, verify
│   │   ├── add-tool/SKILL.md       # /add-tool — guide for adding new MCP tool
│   │   ├── test-tool/SKILL.md      # /test-tool <name> — test MCP tool via stdin
│   │   └── scrum-report/SKILL.md   # /scrum-report — generate sprint summary
│   └── agents/
│       ├── youtrack-explorer.md    # YouTrack API exploration agent
│       └── sheets-formatter.md     # Google Sheets layout agent
├── CLAUDE.md                       # Project instructions
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── domain/
│   ├── build.gradle.kts            # zero external deps
│   └── src/main/java/dev/gbolanos/devtracker/domain/
│       ├── model/
│       │   ├── Ticket.java
│       │   ├── PullRequest.java
│       │   ├── CodeReview.java
│       │   ├── WorkCycle.java
│       │   ├── CycleMetrics.java
│       │   └── SprintSummary.java
│       ├── enums/
│       │   ├── TicketState.java
│       │   ├── TicketType.java
│       │   ├── CycleOrigin.java
│       │   └── EffortLevel.java
│       └── service/
│           ├── EffortCalculator.java
│           └── PointsCalculator.java
├── infrastructure/
│   ├── build.gradle.kts            # depends on domain; google-api-client, gson
│   └── src/main/java/dev/gbolanos/devtracker/infrastructure/
│       ├── youtrack/
│       │   └── YouTrackClient.java
│       ├── github/
│       │   └── GitHubClient.java
│       └── sheets/
│           └── GoogleSheetsClient.java
├── application/
│   ├── build.gradle.kts            # depends on domain + infrastructure
│   └── src/main/java/dev/gbolanos/devtracker/application/
│       ├── GetCycleMetrics.java
│       ├── GetTicketDetails.java
│       ├── GetCodeReviews.java
│       ├── SyncCycleToSheets.java
│       └── GenerateSprintSummary.java
└── server/
    ├── build.gradle.kts            # depends on all; shadow JAR plugin
    └── src/main/
        ├── java/dev/gbolanos/devtracker/server/
        │   ├── Main.java
        │   ├── McpServer.java
        │   ├── JsonRpcHandler.java
        │   └── ToolRegistry.java
        └── resources/
            └── logback.xml
```

---

## Domain Model

### Enums

**TicketState** — `OPEN`, `DEV_READY`, `IN_PROGRESS`, `PENDING_REVIEW`, `PENDING_QA`, `MERGED_CLOSED`, `KICKBACKED`
- "Completed" states (points count): `PENDING_REVIEW`, `PENDING_QA`, `MERGED_CLOSED`
- `KICKBACKED` subtracts points from completed total
- State names are configurable via a mapping to handle YouTrack label differences

**TicketType** — `STORY`, `BUG`, `TASK`, `SPIKE`, `SUBTASK` (extensible)

**CycleOrigin** — `NEW` (brought in this cycle), `ROLLED_OVER` (carried from a prior cycle)
- Determined by: was the ticket added to the sprint/board before or after the cycle start date?

**EffortLevel** — `TRIVIAL`, `SMALL`, `MEDIUM`, `LARGE`, `EXTRA_LARGE`
- Calculated by `EffortCalculator` using composite signal

### Records

**WorkCycle** — `startDate`, `endDate`, `name` (e.g., "2026-Q1 Cycle 1")

**Ticket** — `id`, `title`, `state` (TicketState), `type` (TicketType), `devPoints` (int), `cycleOrigin` (CycleOrigin), `effortLevel` (EffortLevel), `notes` (String), `linkedPRs` (List<PullRequest>), `youtrackUrl` (String)

**PullRequest** — `number`, `repo`, `title`, `author`, `linesAdded`, `linesDeleted`, `filesChanged`, `linkedTicketId` (nullable), `mergedAt` (LocalDate), `url`

**CodeReview** — `prNumber`, `repo`, `prTitle`, `effortLevel` (EffortLevel), `summary` (String), `reviewedAt` (LocalDate), `url`, `linesReviewed`, `filesReviewed`

**CycleMetrics** — `totalTickets`, `newTickets`, `rolledOverTickets`, `totalPoints`, `completedPoints`, `kickbackedPoints`, `completedPointsNew`, `completedPointsRolledOver`, `reviewsPerformed`

**SprintSummary** — `sprintName`, `startDate`, `endDate`, `ticketsByAssignee` (Map<String, List<Ticket>>), `mergedPRsByAuthor` (Map<String, List<PullRequest>>), `totalPointsCompleted`, `totalTicketsClosed`

### Domain Services

**EffortCalculator** — composite signal scoring:

| Signal         | Trivial | Small   | Medium    | Large     | XL       |
|----------------|---------|---------|-----------|-----------|----------|
| Story Points   | 1       | 2-3     | 5         | 8         | 13+      |
| Lines Changed  | <50     | 50-200  | 200-500   | 500-1000  | 1000+    |
| Files Changed  | <3      | 3-5     | 5-10      | 10-20     | 20+      |

Highest signal wins (e.g., 1 point but 800 lines = Large).

**PointsCalculator** — sums points by state, filtering by CycleOrigin. Subtracts kickbacked points.

---

## Infrastructure Clients

### YouTrackClient
`java.net.http.HttpClient` + YouTrack REST API.
- `getIssuesByProject(projectId, startDate, endDate)` — tickets updated in date range
- `getIssueCustomFields(issueId)` — story points, sprint, type
- `getIssueHistory(issueId)` — state transitions (kick-back detection, cycle origin)
- `getAgileBoardSprints(boardId)` — list sprints on a board
- `getSprintIssues(boardId, sprintId)` — all tickets in a sprint (scrum master)

### GitHubClient
`java.net.http.HttpClient` + GitHub REST API (v3).
- `getMergedPRsByUser(username, startDate, endDate)` — PRs authored and merged
- `getReviewsByUser(username, startDate, endDate)` — reviews performed
- `getPRDetails(owner, repo, prNumber)` — lines added/deleted, files changed
- `extractTicketId(branchName, prTitle)` — parse ticket ID from branch or title

### GoogleSheetsClient
Google Sheets API v4 Java client library, service account auth.
- `getOrCreateSpreadsheet(title)` — idempotent create
- `getOrCreateSheet(spreadsheetId, sheetTitle)` — idempotent tab creation
- `writeRows(spreadsheetId, sheetTitle, startCell, rows)` — bulk write
- `formatHeaders(spreadsheetId, sheetTitle)` — bold headers, column widths
- `addHyperlink(cell, url, label)` — for ticket/PR links

---

## Application Use Cases

1. **GetCycleMetrics** — startDate, endDate → CycleMetrics
2. **GetTicketDetails** — startDate, endDate → List<Ticket> (with linked PRs, effort)
3. **GetCodeReviews** — startDate, endDate → List<CodeReview>
4. **SyncCycleToSheets** — startDate, endDate, spreadsheetId? → spreadsheet URL
5. **GenerateSprintSummary** — boardId, sprintName → SprintSummary (formatted text)

---

## MCP Tools

| Tool | Parameters | Description |
|------|-----------|-------------|
| `get_cycle_metrics` | `startDate`, `endDate` | Aggregate metrics for the work cycle |
| `get_ticket_details` | `startDate`, `endDate` | Detailed ticket list with linked PRs and effort |
| `get_code_reviews` | `startDate`, `endDate` | Code reviews performed by the user |
| `sync_cycle_to_sheets` | `startDate`, `endDate`, `spreadsheetId?` | Push cycle data to Google Sheets |
| `generate_sprint_summary` | `boardId`, `sprintName` | Team-wide sprint summary (scrum master) |

---

## Google Sheets Layout

**Tab: "Dashboard"** — Cycle summary (points, completion rate), key contributions (Large/XL efforts), review activity count

**Tab: "Ticket Details"** — Ticket # (linked) | Title | State | Type | Points | Origin | Effort | Notes

**Tab: "Code Reviews"** — PR # (linked) | Repo | Title | Effort | Summary | Date

**Tab: "Cycle Metrics"** — Metric | New | Rolled Over | Total (tickets brought in, points completed, kicked back, net completed)

---

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `YOUTRACK_URL` | Yes | — | YouTrack base URL |
| `YOUTRACK_TOKEN` | Yes | — | YouTrack API token |
| `YOUTRACK_PROJECT_ID` | Yes | — | YouTrack project ID |
| `YOUTRACK_BOARD_ID` | No | — | Agile board ID (scrum master) |
| `YOUTRACK_SP_FIELD` | No | "Story Points" | Story points field name |
| `YOUTRACK_SPRINT_FIELD` | No | "Sprint" | Sprint field name |
| `GITHUB_TOKEN` | Yes | — | GitHub personal access token |
| `GITHUB_USERNAME` | Yes | — | GitHub username |
| `GITHUB_ORG` | No | — | GitHub org (scope repo search) |
| `GOOGLE_SHEETS_CREDENTIALS_PATH` | Yes | — | Path to service account JSON |
| `GOOGLE_SHEETS_SPREADSHEET_ID` | No | — | Reuse existing spreadsheet |

---

## Build Order (execute step by step, verify each before proceeding)

1. **Claude Code project setup** — git init, CLAUDE.md, `.claude/skills/`, `.claude/agents/`, `.claude/settings.json`
2. **Gradle project scaffold** — multi-module setup, dependencies, shadow JAR config, verify `./gradlew build` compiles
3. **Domain enums** — TicketState, TicketType, CycleOrigin, EffortLevel → `./gradlew :domain:build`
4. **Domain records** — Ticket, PullRequest, CodeReview, WorkCycle, CycleMetrics, SprintSummary → `./gradlew :domain:build`
5. **Domain services** — EffortCalculator, PointsCalculator → `./gradlew :domain:build`
6. **YouTrack client** — API integration, issue fetching, history parsing → unit tests
7. **GitHub client** — PR fetching, review fetching, ticket ID extraction → unit tests
8. **Application use cases** — GetTicketDetails, GetCycleMetrics, GetCodeReviews → integration tests
9. **Server + MCP protocol** — Main, McpServer, JsonRpcHandler, ToolRegistry; wire tools 1-3 → build shadow JAR, test via stdin
10. **Google Sheets client** — Sheets API integration → verify sheet creation
11. **SyncCycleToSheets** — wire to MCP as tool 4
12. **GenerateSprintSummary** — wire to MCP as tool 5
