package dev.youagent.search;

import java.util.List;

public record CodeAnalysis(List<CodeChunk> chunks, List<CodeRelation> relations) {
    public CodeAnalysis {
        chunks = List.copyOf(chunks);
        relations = List.copyOf(relations);
    }
}
