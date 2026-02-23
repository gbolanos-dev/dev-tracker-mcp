package dev.gbolanos.devtracker.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonRpcHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcHandler.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ToolRegistry toolRegistry;
    private final Gson gson = new Gson();

    public JsonRpcHandler(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public String handle(String rawJson) {
        JsonObject request;
        try {
            request = JsonParser.parseString(rawJson).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            log.error("Failed to parse JSON-RPC request", e);
            return gson.toJson(errorResponse(null, -32700, "Parse error"));
        }

        var id = request.has("id") ? request.get("id") : null;
        var method = request.has("method") ? request.get("method").getAsString() : "";
        var params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        boolean isNotification = (id == null);

        log.info("Received method: {}", method);

        return switch (method) {
            case "initialize" -> gson.toJson(successResponse(id, initializeResult()));
            case "notifications/initialized" -> null;
            case "tools/list" -> gson.toJson(successResponse(id, toolsListResult()));
            case "tools/call" -> gson.toJson(successResponse(id, toolsCallResult(params)));
            default -> {
                if (isNotification) {
                    log.debug("Ignoring unknown notification: {}", method);
                    yield null;
                }
                log.warn("Unknown method: {}", method);
                yield gson.toJson(errorResponse(id, -32601, "Method not found: " + method));
            }
        };
    }

    private JsonObject initializeResult() {
        var serverInfo = new JsonObject();
        serverInfo.addProperty("name", "dev-tracker-mcp");
        serverInfo.addProperty("version", "1.0.0");

        var capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());

        var result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        return result;
    }

    private JsonObject toolsListResult() {
        var result = new JsonObject();
        result.add("tools", toolRegistry.listTools());
        return result;
    }

    private JsonObject toolsCallResult(JsonObject params) {
        var name = params.has("name") ? params.get("name").getAsString() : "";
        var args = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
        return toolRegistry.callTool(name, args);
    }

    private JsonObject successResponse(Object id, JsonObject result) {
        var response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id != null) {
            response.add("id", gson.toJsonTree(id));
        }
        response.add("result", result);
        return response;
    }

    private JsonObject errorResponse(Object id, int code, String message) {
        var error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        var response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id != null) {
            response.add("id", gson.toJsonTree(id));
        } else {
            response.add("id", null);
        }
        response.add("error", error);
        return response;
    }
}
