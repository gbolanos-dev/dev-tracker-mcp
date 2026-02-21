package dev.gbolanos.devtracker.server;

import com.google.gson.JsonObject;

@FunctionalInterface
interface ToolExecutor {
    Object execute(JsonObject args) throws Exception;
}
