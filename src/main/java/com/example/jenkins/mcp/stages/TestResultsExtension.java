package com.example.jenkins.mcp.stages;

import hudson.model.Run;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.Failure;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import jenkins.util.SystemProperties;
import org.jenkinsci.plugins.variant.OptionalExtension;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

/**
 * Read-only MCP tools for JUnit test results that stay small: a compact list of failing tests, and the details
 * (message, stack trace, stdout, stderr) of one test at a time with size limits. Loaded only if the JUnit plugin is
 * installed.
 */
@OptionalExtension(requirePlugins = "junit")
public class TestResultsExtension implements McpServerExtension {

    private static final int DEFAULT_MAX_TESTS = 50;
    private static final int HARD_MAX_TESTS = 500;
    private static final int DEFAULT_MAX_CHARS = 4000;
    private static final int HARD_MAX_CHARS =
            SystemProperties.getInteger(TestResultsExtension.class.getName() + ".maxChars", 50000);
    private static final int MESSAGE_CHARS = 300;
    private static final int MAX_CANDIDATES_LISTED = 20;

    // ------------------------------------------------------------------ response types

    public record FailedTest(
            String test,
            String className,
            String name,
            String stage,
            String status,
            boolean newInThisBuild,
            int failedSince,
            int age,
            float durationSeconds,
            String message,
            int failedAttempts) {}

    public record FailedTestsSummary(
            String jobFullName,
            int buildNumber,
            int totalCount,
            int failCount,
            int skipCount,
            int newFailureCount,
            int matchingFailures,
            int returnedTests,
            boolean truncated,
            String note,
            List<FailedTest> tests) {}

    public record TextPart(String text, int totalChars, boolean truncated) {}

    public record FailedAttempt(String type, String message) {}

    public record TestCaseDetails(
            String jobFullName,
            int buildNumber,
            String test,
            String className,
            String name,
            String stage,
            String status,
            int failedSince,
            int age,
            float durationSeconds,
            TextPart errorDetails,
            TextPart stackTrace,
            TextPart stdout,
            TextPart stderr,
            boolean stdoutIsSuiteLevel,
            List<FailedAttempt> earlierFailedAttempts,
            String note) {}

    // ------------------------------------------------------------------ tools

    @Tool(
            description = "Compact list of the failing tests of a build: test name, the Pipeline stage it ran in,"
                    + " whether it is new in this build, how long it has been failing, and the first line of the"
                    + " failure message. No stack traces or output; use getTestCaseDetails for one test's details."
                    + " New failures are listed first.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public FailedTestsSummary getFailedTestsSummary(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @ToolParam(description = "Build number (optional, defaults to the last build)", required = false)
                    Integer buildNumber,
            @ToolParam(description = "Maximum number of tests to return (default 50, max 500)", required = false)
                    Integer maxTests,
            @ToolParam(
                            description = "Optional case-insensitive regex matched against the full test name"
                                    + " (class + method), e.g. 'PaymentService' or 'Integration'",
                            required = false)
                    String filter,
            @ToolParam(
                            description = "Optional stage path or prefix, e.g. 'Tests > windows'; only tests"
                                    + " reported from that stage are returned",
                            required = false)
                    String stage) {
        Run<?, ?> run = JenkinsLookup.resolveRun(jobFullName, buildNumber);
        String job = run.getParent().getFullName();
        TestResultAction action = run.getAction(TestResultAction.class);
        if (action == null) {
            return new FailedTestsSummary(job, run.getNumber(), 0, 0, 0, 0, 0, 0, false,
                    "This build has no JUnit test results (the job does not publish test reports, or the build"
                            + " failed before publishing them).",
                    List.of());
        }

        int limit = maxTests == null || maxTests <= 0 ? DEFAULT_MAX_TESTS : Math.min(maxTests, HARD_MAX_TESTS);
        Pattern regex = compile(filter);
        StageResolver stages = new StageResolver(run);

        List<FailedTest> matching = new ArrayList<>();
        int newCount = 0;
        for (CaseResult c : action.getFailedTests()) {
            boolean isNew = c.getFailedSince() == run.getNumber();
            if (isNew) {
                newCount++;
            }
            if (regex != null && !regex.matcher(c.getFullName()).find()) {
                continue;
            }
            String stagePath = stages.pathOf(c);
            if (!stageMatches(stagePath, stage)) {
                continue;
            }
            matching.add(new FailedTest(
                    c.getFullName(),
                    c.getClassName(),
                    c.getName(),
                    stagePath,
                    String.valueOf(c.getStatus()),
                    isNew,
                    c.getFailedSince(),
                    c.getAge(),
                    c.getDuration(),
                    firstLine(c.getErrorDetails(), c.getErrorStackTrace()),
                    c.getFlakyFailures().size() + c.getRerunFailures().size()));
        }
        matching.sort(Comparator.comparing((FailedTest t) -> !t.newInThisBuild())
                .thenComparing(t -> t.stage() == null ? "" : t.stage())
                .thenComparing(FailedTest::test));

        boolean truncated = matching.size() > limit;
        List<FailedTest> returned = truncated ? matching.subList(0, limit) : matching;

        String note;
        if (action.getFailCount() == 0) {
            note = "No failing tests in this build.";
        } else if (matching.isEmpty()) {
            note = "No failing tests match the given filter/stage.";
        } else if (truncated) {
            note = "Showing " + limit + " of " + matching.size()
                    + " matching failures (new ones first). Use 'filter' or 'stage' to narrow down.";
        } else {
            note = "All matching failures returned.";
        }

        return new FailedTestsSummary(
                job,
                run.getNumber(),
                action.getTotalCount(),
                action.getFailCount(),
                action.getSkipCount(),
                newCount,
                matching.size(),
                returned.size(),
                truncated,
                note,
                new ArrayList<>(returned));
    }

    @Tool(
            description = "Details of ONE test case: failure message, stack trace, stdout and stderr, each limited to"
                    + " maxChars. The stack trace and message are cut at the end (top frames kept); stdout and"
                    + " stderr keep their last part (closest to the failure). Use 'pattern' to grep all parts.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public TestCaseDetails getTestCaseDetails(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @ToolParam(description = "Build number (optional, defaults to the last build)", required = false)
                    Integer buildNumber,
            @ToolParam(
                            description = "The test: its full name from getFailedTestsSummary (preferred, e.g."
                                    + " 'com.example.CalcTest.divides'), or just the method name if unique")
                    String test,
            @ToolParam(
                            description = "Optional stage path, needed when the same test ran in several stages",
                            required = false)
                    String stage,
            @ToolParam(
                            description = "Maximum characters per part (default 4000, capped by the server)",
                            required = false)
                    Integer maxChars,
            @ToolParam(
                            description = "Optional case-insensitive regex; each part then contains only matching"
                                    + " lines, prefixed with their line number (e.g. 'L12: ...')",
                            required = false)
                    String pattern) {
        if (test == null || test.isBlank()) {
            throw new IllegalArgumentException("'test' is required: pass a full test name from getFailedTestsSummary");
        }
        Run<?, ?> run = JenkinsLookup.resolveRun(jobFullName, buildNumber);
        TestResultAction action = run.getAction(TestResultAction.class);
        if (action == null) {
            throw new IllegalArgumentException("Build " + run.getFullDisplayName() + " has no JUnit test results");
        }
        int limit = maxChars == null || maxChars <= 0 ? DEFAULT_MAX_CHARS : Math.min(maxChars, HARD_MAX_CHARS);
        Pattern regex = compile(pattern);
        StageResolver stages = new StageResolver(run);

        CaseResult c = findTest(action, stages, test.trim(), stage);

        String stdout = c.getStdout();
        SuiteResult suite = c.getSuiteResult();
        boolean suiteLevel = stdout != null
                && !stdout.isEmpty()
                && suite != null
                && suite.getCases().size() > 1
                && stdout.equals(suite.getStdout());

        List<FailedAttempt> attempts = new ArrayList<>();
        List<Failure> earlier = new ArrayList<>(c.getFlakyFailures());
        earlier.addAll(c.getRerunFailures());
        for (Failure f : earlier) {
            attempts.add(new FailedAttempt(f.type(), firstLine(f.message(), f.stackTrace())));
        }

        String note = c.isFailed()
                ? "Test failed in this build."
                : "Test did not fail in this build (status " + c.getStatus()
                        + "); its output may have been discarded by Jenkins.";
        if (suiteLevel) {
            note += " stdout is the whole suite's output (the report has no per-test output).";
        }

        return new TestCaseDetails(
                run.getParent().getFullName(),
                run.getNumber(),
                c.getFullName(),
                c.getClassName(),
                c.getName(),
                stages.pathOf(c),
                String.valueOf(c.getStatus()),
                c.getFailedSince(),
                c.getAge(),
                c.getDuration(),
                part(c.getErrorDetails(), limit, false, regex),
                part(c.getErrorStackTrace(), limit, false, regex),
                part(stdout, limit, true, regex),
                part(c.getStderr(), limit, true, regex),
                suiteLevel,
                attempts,
                note);
    }

    // ------------------------------------------------------------------ helpers

    private static CaseResult findTest(TestResultAction action, StageResolver stages, String test, String stage) {
        // Failing tests first: that is almost always what is asked for, and it avoids loading every case.
        List<CaseResult> matches = match(action.getFailedTests(), stages, test, stage);
        if (matches.isEmpty()) {
            TestResult result = action.getResult();
            List<CaseResult> all = new ArrayList<>();
            for (SuiteResult s : result.getSuites()) {
                all.addAll(s.getCases());
            }
            matches = match(all, stages, test, stage);
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No test named '" + test + "'"
                    + (stage != null && !stage.isBlank() ? " in stage '" + stage + "'" : "")
                    + ". Use the 'test' value from getFailedTestsSummary.");
        }
        List<String> options = new ArrayList<>();
        for (CaseResult c : matches) {
            if (options.size() >= MAX_CANDIDATES_LISTED) {
                options.add("...");
                break;
            }
            String path = stages.pathOf(c);
            options.add(c.getFullName() + (path != null ? " [stage: " + path + "]" : ""));
        }
        throw new IllegalArgumentException("'" + test + "' matches several tests; pass the full name and, if needed,"
                + " the stage: " + String.join("; ", options));
    }

    private static List<CaseResult> match(List<CaseResult> cases, StageResolver stages, String test, String stage) {
        List<CaseResult> exact = new ArrayList<>();
        List<CaseResult> byName = new ArrayList<>();
        for (CaseResult c : cases) {
            if (!stageMatches(stages.pathOf(c), stage)) {
                continue;
            }
            if (test.equals(c.getFullName()) || test.equals(c.getFullDisplayName())) {
                exact.add(c);
            } else if (test.equals(c.getName())) {
                byName.add(c);
            }
        }
        return exact.isEmpty() ? byName : exact;
    }

    private static boolean stageMatches(String stagePath, String wanted) {
        if (wanted == null || wanted.isBlank()) {
            return true;
        }
        if (stagePath == null) {
            return false;
        }
        String w = wanted.trim();
        return stagePath.equals(w) || stagePath.startsWith(w + " > ");
    }

    private static Pattern compile(String regex) {
        if (regex == null || regex.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex: " + e.getDescription());
        }
    }

    static String firstLine(String primary, String fallback) {
        String text = primary != null && !primary.isBlank() ? primary : fallback;
        if (text == null) {
            return null;
        }
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                String l = line.strip();
                return l.length() > MESSAGE_CHARS ? l.substring(0, MESSAGE_CHARS) + "..." : l;
            }
        }
        return null;
    }

    /** Cuts or greps one text part. {@code keepEnd} keeps the last characters instead of the first ones. */
    static TextPart part(String text, int limit, boolean keepEnd, Pattern regex) {
        if (text == null || text.isEmpty()) {
            return new TextPart("", 0, false);
        }
        String source = text;
        if (regex != null) {
            StringBuilder sb = new StringBuilder();
            String[] lines = text.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                if (regex.matcher(lines[i]).find()) {
                    sb.append('L').append(i + 1).append(": ").append(lines[i]).append('\n');
                }
            }
            source = sb.toString();
        }
        if (source.length() <= limit) {
            return new TextPart(source, text.length(), false);
        }
        String cut = keepEnd
                ? "...[" + (source.length() - limit) + " chars omitted]...\n" + source.substring(source.length() - limit)
                : source.substring(0, limit) + "\n...[" + (source.length() - limit) + " chars omitted]...";
        return new TextPart(cut, text.length(), true);
    }

    /**
     * Maps a test case to the stage path used by getPipelineStages (e.g. "Tests > windows"), using the flow node
     * the JUnit plugin recorded for the report. Builds the stage graph lazily, once per call.
     */
    static final class StageResolver {
        private final Run<?, ?> run;
        private StageGraph graph;
        private boolean graphTried;

        StageResolver(Run<?, ?> run) {
            this.run = run;
        }

        String pathOf(CaseResult c) {
            SuiteResult suite = c.getSuiteResult();
            if (suite == null) {
                return null;
            }
            List<String> ids = new ArrayList<>();
            if (suite.getNodeId() != null) {
                ids.add(suite.getNodeId());
            }
            ids.addAll(suite.getEnclosingBlocks());
            if (ids.isEmpty()) {
                return null;
            }
            StageGraph g = graph();
            if (g != null) {
                for (String id : ids) {
                    FlowNode n = g.byId.get(id);
                    if (n != null) {
                        StageGraph.Stage s = g.stageOf(n);
                        if (s != null) {
                            return g.path(s);
                        }
                    }
                }
            }
            // Fallback: the names the JUnit plugin stored (innermost first); drop the duplicate that Declarative
            // parallel stages produce (branch and stage with the same name).
            List<String> names = new ArrayList<>(suite.getEnclosingBlockNames());
            Collections.reverse(names);
            List<String> cleaned = new ArrayList<>();
            for (String n : names) {
                if (cleaned.isEmpty() || !cleaned.get(cleaned.size() - 1).equals(n)) {
                    cleaned.add(n);
                }
            }
            return cleaned.isEmpty() ? null : String.join(" > ", cleaned);
        }

        private StageGraph graph() {
            if (!graphTried) {
                graphTried = true;
                if (run instanceof WorkflowRun wr) {
                    try {
                        graph = new StageGraph(wr);
                    } catch (RuntimeException e) {
                        graph = null;
                    }
                }
            }
            return graph;
        }
    }
}
