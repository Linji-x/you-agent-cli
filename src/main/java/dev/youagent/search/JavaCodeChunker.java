package dev.youagent.search;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class JavaCodeChunker {
    private final JavaParser parser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    public CodeAnalysis analyze(String relativePath, String source) {
        var parsed = parser.parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            CodeChunk file = chunk(relativePath, relativePath, ChunkType.FILE, 1,
                    Math.max(1, source.lines().toList().size()), source);
            return new CodeAnalysis(List.of(file), List.of());
        }
        CompilationUnit unit = parsed.getResult().orElseThrow();
        List<CodeChunk> chunks = new ArrayList<>();
        List<CodeRelation> relations = new ArrayList<>();
        CodeChunk fileChunk = chunk(relativePath, relativePath, ChunkType.FILE,
                begin(unit), end(unit), source);
        chunks.add(fileChunk);

        for (var imported : unit.getImports()) {
            relations.add(new CodeRelation(fileChunk.id(), imported.getNameAsString(), null,
                    CodeRelation.RelationType.IMPORTS));
        }
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.findAncestor(ClassOrInterfaceDeclaration.class).isPresent()) {
                continue;
            }
            String classSymbol = type.getNameAsString();
            CodeChunk classChunk = chunk(relativePath, classSymbol, ChunkType.CLASS,
                    begin(type), end(type), type.toString());
            chunks.add(classChunk);
            relations.add(new CodeRelation(fileChunk.id(), classSymbol, classChunk.id(),
                    CodeRelation.RelationType.CONTAINS));
            type.getExtendedTypes().forEach(parent -> relations.add(new CodeRelation(classChunk.id(),
                    parent.getNameAsString(), null, CodeRelation.RelationType.EXTENDS)));
            type.getImplementedTypes().forEach(contract -> relations.add(new CodeRelation(classChunk.id(),
                    contract.getNameAsString(), null, CodeRelation.RelationType.IMPLEMENTS)));

            for (MethodDeclaration method : type.getMethods()) {
                String symbol = classSymbol + "#" + method.getSignature().asString();
                CodeChunk methodChunk = chunk(relativePath, symbol, ChunkType.METHOD,
                        begin(method), end(method), method.toString());
                chunks.add(methodChunk);
                relations.add(new CodeRelation(classChunk.id(), symbol, methodChunk.id(),
                        CodeRelation.RelationType.CONTAINS));
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    relations.add(new CodeRelation(methodChunk.id(), call.getNameAsString(), null,
                            CodeRelation.RelationType.CALLS));
                }
            }
        }
        return new CodeAnalysis(chunks, relations);
    }

    private static CodeChunk chunk(String path, String symbol, ChunkType type, int start, int end, String content) {
        String id = sha256(path + "|" + symbol + "|" + type + "|" + start).substring(0, 24);
        return new CodeChunk(id, path, symbol, type, start, end, content, null);
    }

    private static int begin(Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(1);
    }

    private static int end(Node node) {
        return node.getRange().map(range -> range.end.line).orElse(begin(node));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
