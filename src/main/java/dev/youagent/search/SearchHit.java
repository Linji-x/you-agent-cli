package dev.youagent.search;

public record SearchHit(CodeChunk chunk, double score, double lexicalScore, double semanticScore) {
}
