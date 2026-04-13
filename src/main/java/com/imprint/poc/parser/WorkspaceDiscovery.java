package com.imprint.poc.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Locates all VS Code Copilot chatSessions directories on Windows.
 *
 * Structure:
 *   %APPDATA%\Code\User\workspaceStorage\<md5-hash>\
 *     workspace.json          → maps hash to project path
 *     chatSessions\
 *       <uuid>.json           (pre-v1.109)
 *       <uuid>.jsonl          (v1.109+, takes priority)
 *
 *   %APPDATA%\Code\User\globalStorage\emptyWindowChatSessions\
 *       <uuid>.json / .jsonl  (chats without a workspace)
 */
public class WorkspaceDiscovery {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record WorkspaceEntry(Path chatSessionsDir, String workspacePath) {}

    /**
     * Returns all WorkspaceEntry instances found on this machine.
     * workspacePath is "unknown" when workspace.json is missing or unreadable.
     */
    public static List<WorkspaceEntry> discover() throws IOException {
        var appData = System.getenv("APPDATA");
        if (appData == null) {
            throw new IllegalStateException(
                    "%APPDATA% environment variable not set — are you running on Windows?");
        }

        var results = new ArrayList<WorkspaceEntry>();
        var userDir = Path.of(appData, "Code", "User");

        // 1. Workspace-specific sessions
        var workspaceStorage = userDir.resolve("workspaceStorage");
        if (Files.isDirectory(workspaceStorage)) {
            try (Stream<Path> hashes = Files.list(workspaceStorage)) {
                hashes.filter(Files::isDirectory).forEach(hashDir -> {
                    var chatSessions = hashDir.resolve("chatSessions");
                    if (Files.isDirectory(chatSessions)) {
                        var label = readWorkspacePath(hashDir);
                        results.add(new WorkspaceEntry(chatSessions, label));
                    }
                });
            }
        }

        // 2. Global sessions (no workspace open)
        var globalSessions = userDir.resolve("globalStorage").resolve("emptyWindowChatSessions");
        if (Files.isDirectory(globalSessions)) {
            results.add(new WorkspaceEntry(globalSessions, "[no workspace]"));
        }

        return results;
    }

    /**
     * Reads workspace.json to get the human-readable project folder path.
     * Returns "unknown" if the file is absent or malformed.
     */
    private static String readWorkspacePath(Path hashDir) {
        var workspaceJson = hashDir.resolve("workspace.json");
        if (!Files.exists(workspaceJson)) return "unknown";
        try {
            JsonNode root = MAPPER.readTree(workspaceJson.toFile());
            // workspace.json: { "folder": "file:///C:/Users/..." }
            JsonNode folder = root.get("folder");
            if (folder != null) {
                // Strip file:/// prefix for readability
                return folder.asText().replaceFirst("^file:///", "").replace("/", "\\");
            }
        } catch (IOException ignored) {}
        return "unknown";
    }
}
