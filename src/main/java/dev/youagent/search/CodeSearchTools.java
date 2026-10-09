package dev.youagent.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.youagent.tool.Schema;
import dev.youagent.tool.Tool;
import dev.youagent.tool.ToolContext;
import dev.youagent.tool.ToolExecution;
import dev.youagent.tool.ToolRegistry;

import java.util.List;
import java.util.Locale;

public final class CodeSearchTools {
    private static final int MAX_OUTPUT_CHARS = 12_000;
    private static final int MAX_EVIDENCE_CHARS = 1_200;

    private CodeSearchTools() {
    }

    public static void register(ToolRegistry registry, CodeIndexService index) {
        registry.register(new SearchCodeTool(index));
        registry.register(new IndexCodebaseTool(index));
        registry.register(new FindSymbolTool(index));
        registry.register(new FindRelationsTool(index));
        registry.registerResource(index);
    }

    private record SearchCodeTool(CodeIndexService index) implements Tool {
        @Override public String name() { return "search_code"; }
        @Override public String description() {
            return "Search the persisted Java code index by natural-language or symbol query; auto-builds a missing index.";
        }
        @Override public ObjectNode inputSchema() {
            return Schema.optionalInteger(Schema.requiredString(Schema.object(), "query", "Code or behavior to find"),
                    "limit", "Maximum results (1-20)", 5);
        }
        @Override public ToolExecution execute(JsonNode arguments, ToolContext context) throws Exception {
            return ToolExecution.success(formatHits(index.search(requiredText(arguments, "query"), limit(arguments))));
        }
    }

    private record IndexCodebaseTool(CodeIndexService index) implements Tool {
        @Override public String name() { return "index_codebase"; }
        @Override public String description() {
            return "Rebuild the JavaParser/SQLite code index for the current workspace.";
        }
        @Override public ObjectNode inputSchema() { return Schema.object(); }
        @Override public ToolExecution execute(JsonNode arguments, ToolContext context) throws Exception {
            int chunks = index.rebuild();
            return ToolExecution.success("indexed " + chunks + " Java chunks into " + index.database());
        }
    }

    private record FindSymbolTool(CodeIndexService index) implements Tool {
        @Override public String name() { return "find_symbol"; }
        @Override public String description() {
            return "Find Java classes or methods by exact or partial symbol name in the persisted index.";
        }
        @Override public ObjectNode inputSchema() {
            return Schema.optionalInteger(Schema.requiredString(Schema.object(), "symbol", "Class or method symbol"),
                    "limit", "Maximum results (1-20)", 10);
        }
        @Override public ToolExecution execute(JsonNode arguments, ToolContext context) throws Exception {
            return ToolExecution.success(formatHits(index.findSymbol(requiredText(arguments, "symbol"),
                    limit(arguments))));
        }
    }

    private record FindRelationsTool(CodeIndexService index) implements Tool {
        @Override public String name() { return "find_relations"; }
        @Override public String description() {
            return "Find CONTAINS, EXTENDS, IMPLEMENTS, CALLS, and IMPORTS relations for a Java symbol.";
        }
        @Override public ObjectNode inputSchema() {
            return Schema.optionalInteger(Schema.requiredString(Schema.object(), "symbol", "Related symbol"),
                    "limit", "Maximum relations (1-20)", 10);
        }
        @Override public ToolExecution execute(JsonNode arguments, ToolContext context) throws Exception {
            List<CodeRelationHit> relations = index.findRelations(requiredText(arguments, "symbol"), limit(arguments));
            if (relations.isEmpty()) {
                return ToolExecution.success("no relations found");
            }
            StringBuilder output = new StringBuilder();
            for (CodeRelationHit hit : relations) {
                appendBounded(output, String.format(Locale.ROOT, "%s:%d %s --%s--> %s%s%n",
                        hit.sourcePath(), hit.sourceLine(), hit.sourceSymbol(), hit.relation().type(),
                        hit.relation().targetSymbol(), hit.relation().targetId() == null
                                ? "" : " [targetId=" + hit.relation().targetId() + "]"));
            }
            return ToolExecution.success(output.toString().stripTrailing());
        }
    }

    private static String formatHits(List<SearchHit> hits) {
        if (hits.isEmpty()) {
            return "no indexed code matched";
        }
        StringBuilder output = new StringBuilder();
        for (SearchHit hit : hits) {
            CodeChunk chunk = hit.chunk();
            String evidence = chunk.content().strip();
            if (evidence.length() > MAX_EVIDENCE_CHARS) {
                evidence = evidence.substring(0, MAX_EVIDENCE_CHARS) + "\n[evidence truncated]";
            }
            String block = String.format(Locale.ROOT,
                    "score=%.4f lexical=%.4f vector=%.4f %s:%d-%d %s [%s]%n%s%n---%n",
                    hit.score(), hit.lexicalScore(), hit.semanticScore(), chunk.path(), chunk.startLine(),
                    chunk.endLine(), chunk.symbol(), chunk.type(), evidence);
            appendBounded(output, block);
            if (output.length() >= MAX_OUTPUT_CHARS) {
                break;
            }
        }
        return output.toString().stripTrailing();
    }

    private static void appendBounded(StringBuilder target, String value) {
        int remaining = MAX_OUTPUT_CHARS - target.length();
        if (remaining <= 0) {
            return;
        }
        if (value.length() <= remaining) {
            target.append(value);
        } else {
            String marker = "\n[results truncated]";
            int contentLength = Math.max(0, remaining - marker.length());
            target.append(value, 0, contentLength).append(marker);
        }
    }

    private static String requiredText(JsonNode arguments, String field) {
        String value = arguments.path(field).asText().strip();
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static int limit(JsonNode arguments) {
        return Math.max(1, Math.min(20, arguments.path("limit").asInt(5)));
    }
}
