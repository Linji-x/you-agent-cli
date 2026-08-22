package dev.youagent.search;

public record CodeRelationHit(String sourcePath, String sourceSymbol, int sourceLine, CodeRelation relation) {
}
