package dev.youagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class GrepCodeTool implements Tool {
    @Override public String name() { return "grep_code"; }
    @Override public String description() { return "Search UTF-8 workspace files with a regular expression."; }
    @Override public ObjectNode inputSchema() {
        ObjectNode schema = Schema.requiredString(Schema.object(), "query", "Java regular expression");
        schema = Schema.optionalString(schema, "glob", "Optional file glob such as **/*.java");
        return Schema.optionalInteger(schema, "max_results", "Maximum matching lines", 100);
    }

    @Override
    public ToolExecution execute(JsonNode arguments, ToolContext context) throws Exception {
        Pattern query = Pattern.compile(arguments.path("query").asText());
        String glob = arguments.path("glob").asText("**");
        var matcher = context.workspace().getFileSystem().getPathMatcher("glob:" + glob);
        int max = Math.max(1, Math.min(arguments.path("max_results").asInt(100), 500));
        List<String> output = new ArrayList<>();
        try (var stream = Files.walk(context.workspace())) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                Path relative = context.workspace().relativize(file);
                if (!matcher.matches(relative) || Files.size(file) > 2_000_000) {
                    continue;
                }
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (Exception binaryOrUnreadable) {
                    continue;
                }
                for (int i = 0; i < lines.size() && output.size() < max; i++) {
                    if (query.matcher(lines.get(i)).find()) {
                        output.add(relative + ":" + (i + 1) + ":" + lines.get(i).strip());
                    }
                }
                if (output.size() >= max) {
                    break;
                }
            }
        }
        return ToolExecution.success(String.join("\n", output));
    }
}
