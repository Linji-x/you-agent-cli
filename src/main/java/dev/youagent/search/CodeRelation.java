package dev.youagent.search;

public record CodeRelation(String sourceId, String targetSymbol, String targetId, RelationType type) {
    public enum RelationType {
        CONTAINS,
        EXTENDS,
        IMPLEMENTS,
        CALLS,
        IMPORTS
    }
}
