package dev.youagent.search;

import java.io.IOException;

@FunctionalInterface
public interface EmbeddingModel {
    double[] embed(String text) throws IOException;

    default String id() {
        return getClass().getName();
    }
}
