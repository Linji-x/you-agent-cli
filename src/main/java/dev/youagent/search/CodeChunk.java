package dev.youagent.search;

public record CodeChunk(
        String id,
        String path,
        String symbol,
        ChunkType type,
        int startLine,
        int endLine,
        String content,
        double[] embedding
) {
    public CodeChunk {
        embedding = embedding == null ? new double[0] : embedding.clone();
    }

    @Override
    public double[] embedding() {
        return embedding.clone();
    }
}
