package com.imprint.poc;

import com.imprint.poc.llm.LlmProxyClient;
import com.imprint.poc.model.CopilotSession;
import com.imprint.poc.model.SessionSummary;
import com.imprint.poc.parser.SessionParser;
import com.imprint.poc.parser.WorkspaceDiscovery;

import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * POC entry point.
 *
 * Usage:
 *   java -jar imprint-poc.jar            → processes TODAY's sessions
 *   java -jar imprint-poc.jar --all      → processes ALL sessions (for testing)
 *   java -jar imprint-poc.jar --dry-run  → parse only, skip LLM call
 *
 * Environment variables:
 *   LLM_PROXY_URL   http://localhost:8080   (default)
 *   LLM_MODEL       gpt-4o                  (default)
 */
public class ImprintPoc {

    public static void main(String[] args) throws Exception {
        boolean allSessions = Arrays.asList(args).contains("--all");
        boolean dryRun      = Arrays.asList(args).contains("--dry-run");

        System.out.println("=================================================");
        System.out.println(" Imprint — Copilot Transcript POC");
        System.out.println(" Mode  : " + (dryRun ? "DRY RUN (no LLM)" : "FULL"));
        System.out.println(" Filter: " + (allSessions ? "ALL sessions" : "TODAY only"));
        System.out.println("=================================================\n");

        // ── Step 1: Discover all workspace chatSessions directories ──────────
        System.out.println("► Discovering workspaces...");
        var workspaces = WorkspaceDiscovery.discover();
        System.out.println("  Found " + workspaces.size() + " workspace(s)\n");

        if (workspaces.isEmpty()) {
            System.out.println("No Copilot chat sessions found.");
            System.out.println("Expected location: %APPDATA%\\Code\\User\\workspaceStorage\\<hash>\\chatSessions\\");
            return;
        }

        // ── Step 2: Parse sessions ────────────────────────────────────────────
        var today = LocalDate.now(ZoneId.systemDefault());
        var allParsedSessions = new ArrayList<CopilotSession>();
        int skipped = 0;

        for (var workspace : workspaces) {
            System.out.println("► Parsing: " + workspace.workspacePath());
            System.out.println("  Path   : " + workspace.chatSessionsDir());

            try {
                var sessions = SessionParser.parseAll(
                        workspace.chatSessionsDir(), workspace.workspacePath());

                // Filter to today unless --all
                var filtered = allSessions ? sessions : sessions.stream()
                        .filter(s -> {
                            if (s.creationDate().equals(Instant.EPOCH)) return true; // date unknown
                            var sessionDate = s.creationDate()
                                    .atZone(ZoneId.systemDefault()).toLocalDate();
                            return sessionDate.equals(today);
                        })
                        .toList();

                System.out.println("  Sessions on disk : " + sessions.size());
                System.out.println("  After filter     : " + filtered.size());
                allParsedSessions.addAll(filtered);

            } catch (Exception e) {
                System.err.println("  [ERROR] " + e.getMessage());
                skipped++;
            }
            System.out.println();
        }

        System.out.println("─────────────────────────────────────────────────");
        System.out.println("Total sessions to process : " + allParsedSessions.size());
        System.out.println("Workspaces skipped (error): " + skipped);
        System.out.println("─────────────────────────────────────────────────\n");

        if (allParsedSessions.isEmpty()) {
            System.out.println("No sessions found for today.");
            System.out.println("Run with --all to see all historical sessions.");
            return;
        }

        // Print session inventory before LLM step
        printSessionInventory(allParsedSessions);

        if (dryRun) {
            System.out.println("\n[DRY RUN] Skipping LLM calls. Parser is working correctly.");
            return;
        }

        // ── Step 3: LLM extraction ────────────────────────────────────────────
        System.out.println("\n► Sending sessions to LLM proxy...");
        System.out.println("  URL  : " + System.getenv().getOrDefault("LLM_PROXY_URL", "http://localhost:8080"));
        System.out.println("  Model: " + System.getenv().getOrDefault("LLM_MODEL", "gpt-4o") + "\n");

        var client = new LlmProxyClient();
        var summaries = new ArrayList<SessionSummary>();

        for (int i = 0; i < allParsedSessions.size(); i++) {
            var session = allParsedSessions.get(i);
            System.out.printf("  [%d/%d] %s — %s%n",
                    i + 1, allParsedSessions.size(),
                    session.customTitle(), session.workspacePath());
            try {
                var summary = client.summarise(session);
                if (summary != null) {
                    summaries.add(summary);
                    System.out.println("         ✓ Extracted");
                } else {
                    System.out.println("         – Skipped (too short)");
                }
            } catch (Exception e) {
                System.err.println("         ✗ Failed: " + e.getMessage());
            }
        }

        // ── Step 4: Print report ──────────────────────────────────────────────
        printReport(summaries, today);

        // ── Step 5: Save to file ──────────────────────────────────────────────
        saveReport(summaries, today);
    }

    // -------------------------------------------------------------------------
    private static void printSessionInventory(List<CopilotSession> sessions) {
        System.out.println("SESSION INVENTORY");
        System.out.println("─────────────────────────────────────────────────");
        for (var s : sessions) {
            var ts = s.creationDate().equals(Instant.EPOCH) ? "date unknown"
                    : s.creationDate().atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            System.out.printf("  %-45s  %-20s  %d turns%n",
                    truncate(s.customTitle(), 45),
                    truncate(s.workspacePath(), 20),
                    s.requests().size());
            System.out.printf("  %-45s  %s%n", "", ts);
            System.out.println();
        }
    }

    private static void printReport(List<SessionSummary> summaries, LocalDate date) {
        System.out.println("\n═════════════════════════════════════════════════");
        System.out.println(" DAILY WORK SUMMARY — " + date);
        System.out.println("═════════════════════════════════════════════════\n");

        for (var s : summaries) {
            System.out.println("┌─ " + s.sessionTitle() + " [" + s.workspacePath() + "]");
            System.out.println("│  Category : " + s.timeCategory());
            System.out.println("│  Summary  : " + s.rawSummary());

            if (!s.tasksCompleted().isEmpty()) {
                System.out.println("│");
                System.out.println("│  ✓ Completed:");
                s.tasksCompleted().forEach(t -> System.out.println("│    - " + t));
            }
            if (!s.tasksInProgress().isEmpty()) {
                System.out.println("│");
                System.out.println("│  ⏳ In progress:");
                s.tasksInProgress().forEach(t -> System.out.println("│    - " + t));
            }
            if (!s.followUpItems().isEmpty()) {
                System.out.println("│");
                System.out.println("│  → Follow-ups:");
                s.followUpItems().forEach(t -> System.out.println("│    - " + t));
            }
            if (!s.decisionsMade().isEmpty()) {
                System.out.println("│");
                System.out.println("│  ⚡ Decisions:");
                s.decisionsMade().forEach(t -> System.out.println("│    - " + t));
            }
            System.out.println("└─────────────────────────────────────────────\n");
        }

        // Time category breakdown
        System.out.println("TIME CATEGORY BREAKDOWN");
        summaries.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        SessionSummary::timeCategory, java.util.stream.Collectors.counting()))
                .forEach((cat, count) ->
                        System.out.printf("  %-20s %d session(s)%n", cat, count));
    }

    private static void saveReport(List<SessionSummary> summaries, LocalDate date) {
        try {
            var workLogs = Path.of(System.getProperty("user.home"), "Imprint");
            Files.createDirectories(workLogs);

            var fileName = "imprint-" + date + ".md";
            var report   = buildMarkdown(summaries, date);
            var outPath  = workLogs.resolve(fileName);

            Files.writeString(outPath, report);
            System.out.println("\n► Report saved to: " + outPath);

        } catch (Exception e) {
            System.err.println("[WARN] Could not save report: " + e.getMessage());
        }
    }

    private static String buildMarkdown(List<SessionSummary> summaries, LocalDate date) {
        var sb = new StringBuilder();
        sb.append("# Imprint — Daily Work Summary — ").append(date).append("\n\n");

        for (var s : summaries) {
            sb.append("## ").append(s.sessionTitle()).append("\n");
            sb.append("**Workspace:** ").append(s.workspacePath()).append("  \n");
            sb.append("**Category:** ").append(s.timeCategory()).append("  \n\n");
            sb.append(s.rawSummary()).append("\n\n");

            if (!s.tasksCompleted().isEmpty()) {
                sb.append("### Completed\n");
                s.tasksCompleted().forEach(t -> sb.append("- ").append(t).append("\n"));
                sb.append("\n");
            }
            if (!s.tasksInProgress().isEmpty()) {
                sb.append("### In Progress\n");
                s.tasksInProgress().forEach(t -> sb.append("- ").append(t).append("\n"));
                sb.append("\n");
            }
            if (!s.followUpItems().isEmpty()) {
                sb.append("### Follow-ups\n");
                s.followUpItems().forEach(t -> sb.append("- ").append(t).append("\n"));
                sb.append("\n");
            }
            if (!s.decisionsMade().isEmpty()) {
                sb.append("### Decisions\n");
                s.decisionsMade().forEach(t -> sb.append("- ").append(t).append("\n"));
                sb.append("\n");
            }
            sb.append("---\n\n");
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
