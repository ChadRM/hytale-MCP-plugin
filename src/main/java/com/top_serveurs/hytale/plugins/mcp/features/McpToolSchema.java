package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

public final class McpToolSchema {
    private static final Gson GSON = new Gson();
    private static final String SCHEMA_VERSION = "https://json-schema.org/draft/2020-12/schema";

    private McpToolSchema() {
    }

    public static String emptyObjectSchema() {
        return schemaWithProperties(Map.of(), List.of());
    }

    public static String schemaWithProperties(Map<String, JsonObject> properties, List<String> required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("$schema", SCHEMA_VERSION);
        schema.addProperty("type", "object");

        JsonObject propertiesNode = new JsonObject();
        for (Map.Entry<String, JsonObject> entry : properties.entrySet()) {
            propertiesNode.add(entry.getKey(), entry.getValue());
        }
        schema.add("properties", propertiesNode);

        if (!required.isEmpty()) {
            JsonArray requiredNode = new JsonArray();
            for (String name : required) {
                requiredNode.add(name);
            }
            schema.add("required", requiredNode);
        }

        return GSON.toJson(schema);
    }

    public static JsonObject stringProperty(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        addDescription(schema, description);
        return schema;
    }

    public static JsonObject integerProperty(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        addDescription(schema, description);
        return schema;
    }

    public static JsonObject booleanProperty(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "boolean");
        addDescription(schema, description);
        return schema;
    }

    public static JsonObject arrayProperty(JsonObject items, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "array");
        schema.add("items", items);
        addDescription(schema, description);
        return schema;
    }

    public static JsonObject objectProperty(Map<String, JsonObject> properties, List<String> required, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject propertiesNode = new JsonObject();
        for (Map.Entry<String, JsonObject> entry : properties.entrySet()) {
            propertiesNode.add(entry.getKey(), entry.getValue());
        }
        schema.add("properties", propertiesNode);

        if (!required.isEmpty()) {
            JsonArray requiredNode = new JsonArray();
            for (String name : required) {
                requiredNode.add(name);
            }
            schema.add("required", requiredNode);
        }

        addDescription(schema, description);
        return schema;
    }

    private static void addDescription(JsonObject schema, String description) {
        if (description != null && !description.isBlank()) {
            schema.addProperty("description", description);
        }
    }
}
