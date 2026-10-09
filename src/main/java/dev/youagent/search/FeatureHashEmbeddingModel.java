package dev.youagent.search;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Deterministic lexical feature hashing. This is an offline baseline, not a learned semantic embedding.
 */
public final class FeatureHashEmbeddingModel implements EmbeddingModel {
    private final int dimensions;

    public FeatureHashEmbeddingModel(int dimensions) {
        if (dimensions < 32) {
            throw new IllegalArgumentException("dimensions must be at least 32");
        }
        this.dimensions = dimensions;
    }

    @Override
    public double[] embed(String text) {
        double[] vector = new double[dimensions];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^\\p{L}\\p{N}_]+")) {
            if (token.isBlank()) {
                continue;
            }
            long hash = fnv1a(token);
            int index = (int) Math.floorMod(hash, dimensions);
            double sign = (hash & (1L << 32)) == 0 ? 1.0 : -1.0;
            vector[index] += sign * (1.0 + Math.log1p(token.length()));
        }
        normalize(vector);
        return vector;
    }

    @Override
    public String id() {
        return "feature-hash:" + dimensions;
    }

    private static long fnv1a(String token) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : token.getBytes(StandardCharsets.UTF_8)) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static void normalize(double[] vector) {
        double magnitude = 0;
        for (double value : vector) {
            magnitude += value * value;
        }
        if (magnitude == 0) {
            return;
        }
        double divisor = Math.sqrt(magnitude);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= divisor;
        }
    }
}
