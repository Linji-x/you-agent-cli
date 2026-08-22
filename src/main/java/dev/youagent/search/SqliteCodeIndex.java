package dev.youagent.search;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SqliteCodeIndex implements AutoCloseable {
    private final Path workspace;
    private final EmbeddingModel embeddingModel;
    private final JavaCodeChunker chunker = new JavaCodeChunker();
    private final Connection connection;

    public SqliteCodeIndex(Path workspace, Path database, EmbeddingModel embeddingModel) throws SQLException, IOException {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.embeddingModel = embeddingModel;
        Files.createDirectories(database.toAbsolutePath().normalize().getParent());
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().normalize());
        createSchema();
    }

    public synchronized int rebuild() throws IOException, SQLException {
        List<Path> files;
        try (var stream = Files.walk(workspace)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !ignored(path))
                    .sorted()
                    .toList();
        }
        connection.setAutoCommit(false);
        try {
            connection.createStatement().executeUpdate("DELETE FROM relations");
            connection.createStatement().executeUpdate("DELETE FROM chunks");
            int count = 0;
            for (Path file : files) {
                String relative = workspace.relativize(file).toString().replace('\\', '/');
                CodeAnalysis analysis = chunker.analyze(relative, Files.readString(file, StandardCharsets.UTF_8));
                for (CodeChunk chunk : analysis.chunks()) {
                    insertChunk(new CodeChunk(chunk.id(), chunk.path(), chunk.symbol(), chunk.type(),
                            chunk.startLine(), chunk.endLine(), chunk.content(), embeddingModel.embed(chunk.content())));
                    count++;
                }
                for (CodeRelation relation : analysis.relations()) {
                    insertRelation(relation);
                }
            }
            connection.commit();
            return count;
        } catch (IOException | SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized List<SearchHit> search(String query, int limit) throws SQLException, IOException {
        double[] queryVector = embeddingModel.embed(query);
        Set<String> queryTerms = tokenize(query);
        List<SearchHit> hits = new ArrayList<>();
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id,path,symbol,type,start_line,end_line,content,embedding FROM chunks")) {
            while (rows.next()) {
                CodeChunk chunk = readChunk(rows);
                double lexical = lexicalScore(chunk, queryTerms);
                double semantic = cosine(queryVector, chunk.embedding());
                double typeBoost = switch (chunk.type()) {
                    case METHOD -> 0.08;
                    case CLASS -> 0.04;
                    case FILE -> 0.0;
                };
                double score = 0.52 * semantic + 0.40 * lexical + typeBoost;
                hits.add(new SearchHit(chunk, score, lexical, semantic));
            }
        }
        return hits.stream()
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed()
                        .thenComparing(hit -> hit.chunk().path())
                        .thenComparing(hit -> hit.chunk().symbol()))
                .limit(Math.max(1, limit))
                .toList();
    }

    public synchronized List<CodeRelation> relationsFor(String symbol) throws SQLException {
        String sql = """
                SELECT r.source_id,r.target_symbol,r.target_id,r.type
                FROM relations r JOIN chunks c ON c.id=r.source_id
                WHERE c.symbol=? OR r.target_symbol=?
                ORDER BY r.type,r.target_symbol
                """;
        List<CodeRelation> relations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setString(2, symbol);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    relations.add(new CodeRelation(rows.getString(1), rows.getString(2), rows.getString(3),
                            CodeRelation.RelationType.valueOf(rows.getString(4))));
                }
            }
        }
        return relations;
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    private void createSchema() throws SQLException {
        connection.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS chunks(
                  id TEXT PRIMARY KEY,
                  path TEXT NOT NULL,
                  symbol TEXT NOT NULL,
                  type TEXT NOT NULL,
                  start_line INTEGER NOT NULL,
                  end_line INTEGER NOT NULL,
                  content TEXT NOT NULL,
                  embedding BLOB NOT NULL
                )
                """);
        connection.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS relations(
                  source_id TEXT NOT NULL,
                  target_symbol TEXT NOT NULL,
                  target_id TEXT,
                  type TEXT NOT NULL
                )
                """);
        connection.createStatement().executeUpdate("CREATE INDEX IF NOT EXISTS idx_chunks_symbol ON chunks(symbol)");
        connection.createStatement().executeUpdate("CREATE INDEX IF NOT EXISTS idx_rel_source ON relations(source_id)");
    }

    private void insertChunk(CodeChunk chunk) throws SQLException, IOException {
        String sql = "INSERT INTO chunks(id,path,symbol,type,start_line,end_line,content,embedding) VALUES(?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, chunk.id());
            statement.setString(2, chunk.path());
            statement.setString(3, chunk.symbol());
            statement.setString(4, chunk.type().name());
            statement.setInt(5, chunk.startLine());
            statement.setInt(6, chunk.endLine());
            statement.setString(7, chunk.content());
            statement.setBytes(8, encode(chunk.embedding()));
            statement.executeUpdate();
        }
    }

    private void insertRelation(CodeRelation relation) throws SQLException {
        String sql = "INSERT INTO relations(source_id,target_symbol,target_id,type) VALUES(?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, relation.sourceId());
            statement.setString(2, relation.targetSymbol());
            statement.setString(3, relation.targetId());
            statement.setString(4, relation.type().name());
            statement.executeUpdate();
        }
    }

    private CodeChunk readChunk(ResultSet row) throws SQLException, IOException {
        return new CodeChunk(row.getString("id"), row.getString("path"), row.getString("symbol"),
                ChunkType.valueOf(row.getString("type")), row.getInt("start_line"), row.getInt("end_line"),
                row.getString("content"), decode(row.getBytes("embedding")));
    }

    private boolean ignored(Path path) {
        Path relative = workspace.relativize(path);
        for (Path part : relative) {
            String value = part.toString();
            if (value.equals("target") || value.equals(".git") || value.equals(".tools") || value.equals(".you-agent")) {
                return true;
            }
        }
        return false;
    }

    private static byte[] encode(double[] vector) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(vector.length);
            for (double value : vector) {
                output.writeDouble(value);
            }
        }
        return bytes.toByteArray();
    }

    private static double[] decode(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            double[] vector = new double[input.readInt()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = input.readDouble();
            }
            return vector;
        }
    }

    private static Set<String> tokenize(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (!token.isBlank()) {
                terms.add(token);
            }
        }
        return terms;
    }

    private static double lexicalScore(CodeChunk chunk, Set<String> queryTerms) {
        if (queryTerms.isEmpty()) {
            return 0;
        }
        String haystack = (chunk.symbol() + " " + chunk.content()).toLowerCase(Locale.ROOT);
        long matches = queryTerms.stream().filter(haystack::contains).count();
        double coverage = matches / (double) queryTerms.size();
        boolean exactSymbol = queryTerms.stream().anyMatch(term -> chunk.symbol().toLowerCase(Locale.ROOT).contains(term));
        return Math.min(1.0, coverage + (exactSymbol ? 0.2 : 0));
    }

    private static double cosine(double[] left, double[] right) {
        if (left.length != right.length || left.length == 0) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
        }
        return Math.max(0, dot);
    }
}
