# /scrum-report — Generate sprint summary

Generate a sprint summary report using the `generate_sprint_summary` tool.

## Steps

1. Ask the user for: board ID and sprint name (or use defaults from env vars)
2. Build the shadow JAR if needed: `./gradlew :server:shadowJar`
3. Call `generate_sprint_summary` via JSON-RPC:
   ```bash
   echo '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"generate_sprint_summary","arguments":{"boardId":"<id>","sprintName":"<name>"}}}' | java -jar server/build/libs/dev-tracker-mcp.jar
   ```
4. Parse and format the response into a readable sprint summary
5. Present: tickets by assignee, PRs merged, points completed, blockers
