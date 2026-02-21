package dev.gbolanos.devtracker.server;

import com.google.gson.JsonObject;

record ToolDefinition(String name, String description, JsonObject inputSchema, ToolExecutor executor) {}
