package com.imprint.poc.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.imprint.poc.model.CopilotRequest;
import com.imprint.poc.model.CopilotSession;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Parses Copilot chat session files into CopilotSession records.
 *
 * Handles two formats:
 *  - .json  (pre VS Code v1.109): full JSON snapshot per session
 *  - .jsonl (v1.109+):            append-only mutation log, must be replayed
 *
 * When both exist for the same UUID, .jsonl takes priority (per VS Code behaviour).
 */
public class SessionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * Parses all sessions in the given chatSessions directory.
     * Automatically deduplicates: .jsonl wins over .json for the same UUID.
     *
     * @param chatSessionsDir  path to a chatSessions\ folder
     * @param workspacePath    human-readable project label (for display only)
     */
    public static List<CopilotSession> parseAll(Path chatSessionsDir,
                                                 String workspacePath) throws IOException {
        // Collect files, grouped by UUID (filename without extension)
        Map<String, Path> jsonlFiles = new LinkedHashMap<>();
        Map<String, Path> jsonFiles  = new LinkedHashMap<>();

        try (Stream<Path> files = Files.list(chatSessionsDir)) {
            files.filter(Files::isRegularFile).forEach(f -> {
                var name = f.getFileName().toString();
                if (name.endsWith(".jsonl")) {
                    jsonlFiles.put(stripExtension(name), f);
                } else if (name.endsWith(".json")) {
                    jsonFiles.put(stripExtension(name), f);
                }
            });
        }

        var sessions = new ArrayList<CopilotSession>();

        // .jsonl takes priority; fall back to .json for UUIDs not in jsonl set
        var allUuids = new LinkedHashSet<String>();
        allUuids.addAll(jsonlFiles.keySet());
        allUuids.addAll(jsonFiles.keySet());

        for (var uuid : allUuids) {
            try {
                CopilotSession session;
                if (jsonlFiles.containsKey(uuid)) {
                    session = parseJsonl(jsonlFiles.get(uuid), workspacePath);
                } else {
                    session = parseJson(jsonFiles.get(uuid), workspacePath);
                }
                if (session != null) sessions.add(session);
            } catch (Exception e) {
                System.err.println("[WARN] Skipping unreadable session " + uuid + ": " + e.getMessage());
            }
        }

        return sessions;
    }

    // -------------------------------------------------------------------------
    // .json parser (pre-v1.109 — full snapshot)
    // -------------------------------------------------------------------------
    private static CopilotSession parseJson(Path file, String workspacePath) throws IOException {
        JsonNode root = MAPPER.readTree(file.toFile());
        return buildSession(root, file, workspacePath);
    }

    // -------------------------------------------------------------------------
    // .jsonl parser (v1.109+ — mutation log replay)
    //
    // Each line is: { "kind": <int>, "key": "<field>", "value": <json> }
    //   kind 0 = initial full document
    //   kind 1 = set field
    //   kind 2 = push to array
    //   kind 3 = delete field
    // -------------------------------------------------------------------------
    private static CopilotSession parseJsonl(Path file, String workspacePath) throws IOException {
        // Replay mutations into a mutable map that mirrors the session JSON structure
        Map<String, JsonNode> state = new LinkedHashMap<>();

        for (var line : Files.readAllLines(file)) {
            line = line.trim();
            if (line.isEmpty()) continue;

            JsonNode op = MAPPER.readTree(line);
            int kind = op.path("kind").asInt(-1);

            switch (kind) {
                case 0 -> {
                    // Initial document — seed all fields
                    JsonNode value = op.get("value");
                    if (value != null && value.isObject()) {
                        value.fields().forEachRemaining(e -> state.put(e.getKey(), e.getValue()));
                    }
                }
                case 1 -> {
                    // Set field
                    String key = op.path("key").asText();
                    JsonNode value = op.get("value");
                    if (key != null && value != null) state.put(key, value);
                }
                case 2 -> {
                    // Push to array field
                    String key = op.path("key").asText();
                    JsonNode item = op.get("value");
                    if (key != null && item != null) {
                        var existing = state.computeIfAbsent(key,
                                k -> MAPPER.createArrayNode());
                        if (existing.isArray()) {
                            ((com.fasterxml.jackson.databind.node.ArrayNode) existing).add(item);
                        }
                    }
                }
                case 3 -> {
                    // Delete field
                    String key = op.path("key").asText();
                    if (key != null) state.remove(key);
                }
                default -> { /* unknown kind, skip */ }
            }
        }

        // Reconstruct a synthetic JSON object and parse it like a .json file
        var syntheticRoot = MAPPER.createObjectNode();
        state.forEach(syntheticRoot::set);
        return buildSession(syntheticRoot, file, workspacePath);
    }

    // -------------------------------------------------------------------------
    // Shared builder — works on the reconstructed JSON tree
    // -------------------------------------------------------------------------
    private static CopilotSession buildSession(JsonNode root, Path sourceFile,
                                                String workspacePath) {
        var sessionId    = root.path("sessionId").asText(sourceFile.getFileName().toString());
        var customTitle  = root.path("customTitle").asText("Untitled");
        var creationDate = parseInstant(root.path("creationDate"));

        var requests = new ArrayList<CopilotRequest>();
        JsonNode requestsNode = root.path("requests");

        if (requestsNode.isArray()) {
            for (JsonNode reqNode : requestsNode) {
                var requestId = reqNode.path("requestId").asText("");

                // User message: may be under "message" or "message.text"
                String userMsg = extractText(reqNode.path("message"));

                // Assistant response: collect all text-type response items
                String assistantText = extractAssistantText(reqNode.path("response"));

                // Model used for this request
                String modelId = reqNode.path("modelId").asText(
                        reqNode.path("agent").asText("unknown"));

                if (!userMsg.isBlank()) {
                    requests.add(new CopilotRequest(requestId, userMsg, assistantText, modelId));
                }
            }
        }

        if (requests.isEmpty()) return null; // skip empty/stub sessions

        return new CopilotSession(sessionId, customTitle, creationDate,
                workspacePath, sourceFile.toString(), requests);
    }

    /**
     * Extracts text from a message node.
     * VS Code stores it as either a plain string or { "value": "..." }.
     */
    private static String extractText(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isObject()) {
            JsonNode value = node.get("value");
            if (value != null) return value.asText();
        }
        return "";
    }

    /**
     * Extracts assistant text from the response array.
     * Skips toolInvocationSerialized entries; collects markdown text parts.
     */
    private static String extractAssistantText(JsonNode responseNode) {
        if (!responseNode.isArray()) return "";
        var sb = new StringBuilder();
        for (JsonNode item : responseNode) {
            // toolInvocationSerialized entries have a "toolId" field — skip them
            if (item.has("toolId") || item.has("toolInvocationSerialized")) continue;
            String part = extractText(item);
            if (!part.isBlank()) sb.append(part).append("\n");
        }
        return sb.toString().trim();
    }

    private static Instant parseInstant(JsonNode node) {
        if (node.isNumber()) return Instant.ofEpochMilli(node.asLong());
        if (node.isTextual()) {
            try { return Instant.parse(node.asText()); } catch (Exception ignored) {}
        }
        return Instant.EPOCH;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
