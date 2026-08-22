package dev.youagent.search;

import dev.youagent.config.EmbeddingConfig;

public final class EmbeddingModels {
    private EmbeddingModels() {
    }

    public static EmbeddingModel from(EmbeddingConfig config) {
        return config.remoteConfigured()
                ? new OpenAiCompatibleEmbeddingModel(config)
                : new FeatureHashEmbeddingModel(config.featureHashDimensions());
    }
}
