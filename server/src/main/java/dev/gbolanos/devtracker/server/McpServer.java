package dev.gbolanos.devtracker.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;

public class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    private final JsonRpcHandler handler;

    public McpServer(JsonRpcHandler handler) {
        this.handler = handler;
    }

    public void start() {
        log.info("MCP server listening on stdin/stdout");
        try (var reader = new BufferedReader(new InputStreamReader(System.in));
             var writer = new PrintWriter(System.out, true)) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                log.debug("Received: {}", line);
                var response = handler.handle(line);
                if (response != null) {
                    writer.println(response);
                    log.debug("Sent: {}", response);
                }
            }
        } catch (IOException e) {
            log.error("IO error in MCP server loop", e);
        }
        log.info("MCP server shutting down (stdin closed)");
    }
}
