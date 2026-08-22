package dev.youagent.search;

@FunctionalInterface
public interface EmbeddingModel {
    double[] embed(String text);
}
