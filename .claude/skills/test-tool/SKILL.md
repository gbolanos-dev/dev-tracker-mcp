# /test-tool <name> — Test an MCP tool via stdin

Test a specific MCP tool by sending JSON-RPC messages to the shadow JAR.

## Arguments

- `name` — the MCP tool name (e.g., `get_cycle_metrics`)

## Steps

1. Build the shadow JAR: `./gradlew :server:shadowJar`
2. Send the `initialize` handshake:
   ```bash
   echo '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}' | java -jar server/build/libs/dev-tracker-mcp.jar
   ```
3. Send a `tools/list` request to confirm the tool is registered
4. Send a `tools/call` request with sample arguments for the specified tool
5. Validate the response: check for errors, verify the output structure
6. Report results: success/failure, response content, any issues found
