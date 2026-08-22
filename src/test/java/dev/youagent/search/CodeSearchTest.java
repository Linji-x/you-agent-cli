package dev.youagent.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSearchTest {
    @Test
    void indexesAstChunksRanksMethodAndStoresRelations(@TempDir Path workspace) throws Exception {
        Path source = workspace.resolve("src/AuthService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package sample;
                import java.util.Objects;
                public final class AuthService implements AutoCloseable {
                    public boolean authenticate(String token) {
                        return Objects.requireNonNull(token).startsWith("user-");
                    }
                    public void close() { }
                }
                """);
        Path database = workspace.resolve(".you-agent/index.db");

        try (SqliteCodeIndex index = new SqliteCodeIndex(workspace, database, new HashEmbeddingModel(128))) {
            int chunks = index.rebuild();
            var hits = index.search("authenticate user token", 5);

            assertTrue(chunks >= 4);
            assertFalse(hits.isEmpty());
            assertEquals(ChunkType.METHOD, hits.get(0).chunk().type());
            assertTrue(hits.get(0).chunk().symbol().contains("AuthService#authenticate"));
            assertTrue(index.relationsFor("AuthService").stream()
                    .anyMatch(relation -> relation.type() == CodeRelation.RelationType.CONTAINS));
        }
    }
}
