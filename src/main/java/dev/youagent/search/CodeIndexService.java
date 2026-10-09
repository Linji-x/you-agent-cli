package dev.youagent.search;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

public final class CodeIndexService implements AutoCloseable {
    private final Path workspace;
    private final Path database;
    private final EmbeddingModel embeddingModel;
    private SqliteCodeIndex index;

    public CodeIndexService(Path workspace, Path database, EmbeddingModel embeddingModel) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.database = database.toAbsolutePath().normalize();
        this.embeddingModel = embeddingModel;
    }

    public synchronized int rebuild() throws IOException, SQLException {
        return index().rebuild();
    }

    public synchronized List<SearchHit> search(String query, int limit) throws IOException, SQLException {
        ensureIndexed();
        return index().search(query, limit);
    }

    public synchronized List<SearchHit> findSymbol(String symbol, int limit) throws IOException, SQLException {
        ensureIndexed();
        return index().findSymbol(symbol, limit);
    }

    public synchronized List<CodeRelationHit> findRelations(String symbol, int limit) throws IOException, SQLException {
        ensureIndexed();
        return index().relationHitsFor(symbol, limit);
    }

    public Path database() {
        return database;
    }

    @Override
    public synchronized void close() throws SQLException {
        if (index != null) {
            index.close();
            index = null;
        }
    }

    private void ensureIndexed() throws IOException, SQLException {
        SqliteCodeIndex current = index();
        if (!current.hasChunks() || !current.usesCurrentEmbedding()) {
            current.rebuild();
        }
    }

    private SqliteCodeIndex index() throws IOException, SQLException {
        if (index == null) {
            index = new SqliteCodeIndex(workspace, database, embeddingModel);
        }
        return index;
    }
}
