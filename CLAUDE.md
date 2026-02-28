# Dev Tracker MCP

MCP server (JSON-RPC 2.0 over stdio) that tracks engineering contributions across YouTrack, GitHub, and Google Sheets for a Senior Engineer promotion case.

## Tech Stack

- Java 17 (Zulu/Azul vendor)
- Gradle 9.3.1 with Kotlin DSL
- Shadow JAR for packaging
- `java.net.http.HttpClient` for HTTP (no extra HTTP libraries)
- Logback for logging (file-only, stdout stays clean for JSON-RPC)
- Google Sheets API v4 Java client library (service account auth)
- MCP JSON-RPC protocol version: `2024-11-05`

## Module Structure

```
domain/         → Java records + enums: Ticket, PullRequest, CodeReview, WorkCycle, etc.
infrastructure/ → YouTrackClient, GitHubClient, GoogleSheetsClient
application/    → Use cases: GetCycleMetrics, GetTicketDetails, GetCodeReviews, SyncCycleToSheets, GenerateSprintSummary
server/         → Main, McpServer, JsonRpcHandler, ToolRegistry + logback.xml
```

Package base: `dev.gbolanos.devtracker`

## Build Commands

```bash
./gradlew build                    # Build all modules
./gradlew :domain:build            # Build domain only
./gradlew :infrastructure:build    # Build infrastructure only
./gradlew :application:build       # Build application only
./gradlew :server:shadowJar        # Build fat JAR
```

Shadow JAR output: `server/build/libs/dev-tracker-mcp.jar`

## Testing MCP Tools

Pipe JSON-RPC messages to the shadow JAR via stdin:

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_cycle_metrics","arguments":{"startDate":"2026-01-05","endDate":"2026-02-13"}}}' | java -jar server/build/libs/dev-tracker-mcp.jar
```

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `YOUTRACK_URL` | Yes | — | YouTrack base URL |
| `YOUTRACK_TOKEN` | Yes | — | YouTrack API token |
| `YOUTRACK_BOARDS` | No | — | Comma-separated board names. Projects derived automatically. |
| `YOUTRACK_LEGACY_BOARD` | No | — | Legacy board name for older sprint name fallback. |
| `YOUTRACK_SP_FIELD` | No | "Story Points" | Story points field name |
| `YOUTRACK_SPRINT_FIELD` | No | "Sprint" | Sprint field name |
| `YOUTRACK_ASSIGNEE_FIELD` | No | "Assignee" | YouTrack assignee field name |
| `YOUTRACK_PRIMARY_DEV_FIELD` | No | "Primary Dev" | YouTrack primary developer field name |
| `GITHUB_API_URL` | No | `https://api.github.com` | GitHub API base URL (use `https://<host>/api/v3` for GHE) |
| `GITHUB_TOKEN` | Yes | — | GitHub personal access token |
| `GITHUB_USERNAME` | Yes | — | GitHub username |
| `GITHUB_ORG` | No | — | GitHub org (scope repo search) |
| `GOOGLE_SHEETS_CREDENTIALS_PATH` | Yes | — | Path to service account JSON |
| `GOOGLE_SHEETS_SPREADSHEET_ID` | No | — | Reuse existing spreadsheet |

## Logging

- Log file: `~/.dev-tracker-mcp/logs/server.log`
- **Never** log to stdout — it's reserved for JSON-RPC communication
- Use SLF4J: `private static final Logger log = LoggerFactory.getLogger(MyClass.class);`

## Coding Conventions

- Java records for immutable data (domain model)
- No Lombok — use records and explicit constructors
- Package-per-feature within each module
- Clients fail lazily: warn at startup if env vars missing, throw at call time
- All dates use `java.time.LocalDate` with ISO format (`yyyy-MM-dd`)

## MCP Tools

| Tool | Parameters | Description |
|------|-----------|-------------|
| `get_cycle_metrics` | `startDate?`, `endDate?`, `sprintName?`, `project?` | Aggregate metrics for the work cycle |
| `get_ticket_details` | `startDate?`, `endDate?`, `sprintName?`, `project?` | Detailed ticket list with linked PRs and effort |
| `get_code_reviews` | `startDate?`, `endDate?`, `sprintName?` | Code reviews performed by the user |
| `sync_cycle_to_sheets` | `startDate`, `endDate`, `spreadsheetId?` | Push cycle data to Google Sheets |
| `generate_sprint_summary` | `boardId`, `sprintName` | Team-wide sprint summary (scrum master) |

## Git Workflow

- `main` branch is the stable base
- Feature branches: `feature/<name>` → PR → merge to `main`
- Each PR should pass `./gradlew build` before merging
