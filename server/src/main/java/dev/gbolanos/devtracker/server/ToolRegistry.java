package dev.gbolanos.devtracker.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();
    private final Gson gson;

    public ToolRegistry() {
        this.gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class,
                        (JsonSerializer<LocalDate>) (src, type, ctx) ->
                                ctx.serialize(src.format(DateTimeFormatter.ISO_LOCAL_DATE)))
                .create();
    }

    public void register(ToolDefinition tool) {
        tools.put(tool.name(), tool);
        log.info("Registered tool: {}", tool.name());
    }

    public JsonArray listTools() {
        var array = new JsonArray();
        for (var tool : tools.values()) {
            var obj = new JsonObject();
            obj.addProperty("name", tool.name());
            obj.addProperty("description", tool.description());
            obj.add("inputSchema", tool.inputSchema());
            array.add(obj);
        }
        return array;
    }

    public JsonObject callTool(String name, JsonObject args) {
        var tool = tools.get(name);
        if (tool == null) {
            return errorContent("Unknown tool: " + name);
        }
        try {
            var result = tool.executor().execute(args);
            var text = gson.toJson(result);
            return successContent(text);
        } catch (Exception e) {
            log.error("Tool '{}' failed", name, e);
            return errorContent(e.getMessage());
        }
    }

    private JsonObject successContent(String text) {
        var content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);

        var array = new JsonArray();
        array.add(content);

        var result = new JsonObject();
        result.add("content", array);
        return result;
    }

    private JsonObject errorContent(String message) {
        var content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", message);

        var array = new JsonArray();
        array.add(content);

        var result = new JsonObject();
        result.add("content", array);
        result.addProperty("isError", true);
        return result;
    }
}
