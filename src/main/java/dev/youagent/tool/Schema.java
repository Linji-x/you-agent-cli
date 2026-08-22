package dev.youagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class Schema {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Schema() {
    }

    public static ObjectNode object() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        root.set("properties", MAPPER.createObjectNode());
        root.set("required", MAPPER.createArrayNode());
        root.put("additionalProperties", false);
        return root;
    }

    public static ObjectNode requiredString(ObjectNode root, String name, String description) {
        property(root, name, "string", description);
        ((ArrayNode) root.path("required")).add(name);
        return root;
    }

    public static ObjectNode optionalString(ObjectNode root, String name, String description) {
        return property(root, name, "string", description);
    }

    public static ObjectNode optionalInteger(ObjectNode root, String name, String description, int defaultValue) {
        ObjectNode property = property(root, name, "integer", description).withObject("/properties/" + name);
        property.put("default", defaultValue);
        return root;
    }

    public static ObjectNode requiredStringArray(ObjectNode root, String name, String description) {
        ObjectNode property = property(root, name, "array", description).withObject("/properties/" + name);
        property.putObject("items").put("type", "string");
        ((ArrayNode) root.path("required")).add(name);
        return root;
    }

    private static ObjectNode property(ObjectNode root, String name, String type, String description) {
        ObjectNode node = ((ObjectNode) root.path("properties")).putObject(name);
        node.put("type", type);
        node.put("description", description);
        return root;
    }
}
