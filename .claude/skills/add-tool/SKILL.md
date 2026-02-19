# /add-tool — Add a new MCP tool

Guide for adding a new MCP tool to the server.

## Steps

1. **Domain**: Add any new records/enums to `domain/src/main/java/dev/gbolanos/devtracker/domain/`
2. **Infrastructure**: If the tool needs external API calls, add/update a client in `infrastructure/`
3. **Application**: Create a use case class in `application/src/main/java/dev/gbolanos/devtracker/application/`
4. **Server**: Register the tool in `ToolRegistry.java`:
   - Add a tool definition with name, description, and input schema
   - Add a handler that parses arguments, calls the use case, and returns JSON
5. **Verify**: Run `./gradlew :server:shadowJar` and test via stdin:
   ```bash
   echo '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"<tool_name>","arguments":{...}}}' | java -jar server/build/libs/dev-tracker-mcp.jar
   ```
6. **Update CLAUDE.md**: Add the tool to the MCP Tools table
