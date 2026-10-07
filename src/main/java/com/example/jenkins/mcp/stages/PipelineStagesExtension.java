package com.example.jenkins.mcp.stages;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.console.AnnotatedLargeText;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

/**
 * Read-only MCP tools that expose Pipeline stages and per-stage logs, so a client does not have to untangle
 * interleaved parallel output from the full console log.
 */
@Extension
public class PipelineStagesExtension implements McpServerExtension {

    private static final int DEFAULT_MAX_LINES = 200;
    private static final int HARD_MAX_LINES =
            SystemProperties.getInteger(PipelineStagesExtension.class.getName() + ".maxLines", 5000);

    // ------------------------------------------------------------------ response types

    public record StageInfo(
            String id,
            String name,
            String type,
            String path,
            String parentId,
            String status,
            String skipReason,
            String startTime,
            Long durationMillis,
            String problem,
            String problemStepId,
            String problemStep) {}

    public record PipelineStagesResponse(
            String jobFullName,
            int buildNumber,
            String buildResult,
            boolean building,
            int stageCount,
            List<StageInfo> failedLeafStages,
            List<StageInfo> stages) {}

    public record StageLogResponse(
            String jobFullName,
            int buildNumber,
            String targetId,
            String targetName,
            String targetPath,
            String status,
            int stepsWithLogs,
            long totalLines,
            Long matchedLines,
            int returnedLines,
            boolean truncated,
            String note,
            String log) {}

    // ------------------------------------------------------------------ tools

    @Tool(
            description = "Lists the stages and parallel branches of a Pipeline build with their status, duration and,"
                    + " for problems, the error and the step that caused it. 'failedLeafStages' lists the innermost"
                    + " stages/branches that failed (the real culprits; parents fail only because a child did),"
                    + " earliest failure first. Use the returned 'id' or 'problemStepId' with getStageLog.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public PipelineStagesResponse getPipelineStages(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @ToolParam(description = "Build number (optional, defaults to the last build)", required = false)
                    Integer buildNumber,
            @ToolParam(
                            description = "If true, 'stages' only contains stages/branches that are not SUCCESS or"
                                    + " SKIPPED. Recommended for pipelines with many parallel branches.",
                            required = false)
                    Boolean onlyProblems) {
        WorkflowRun run = resolveRun(jobFullName, buildNumber);
        StageGraph graph = new StageGraph(run);

        List<StageGraph.Stage> visible = graph.visibleStages();
        List<StageInfo> all = new ArrayList<>();
        List<StageGraph.Stage> problems = new ArrayList<>();
        List<StageInfo> listed = new ArrayList<>();
        for (StageGraph.Stage s : visible) {
            StageInfo info = toInfo(graph, s);
            all.add(info);
            if (StageGraph.PROBLEM_STATUSES.contains(info.status())) {
                problems.add(s);
            }
            if (!Boolean.TRUE.equals(onlyProblems)
                    || !("SUCCESS".equals(info.status()) || "SKIPPED".equals(info.status()))) {
                listed.add(info);
            }
        }

        // Leaves: problem stages without a problem descendant.
        List<StageGraph.Stage> leaves = new ArrayList<>();
        for (StageGraph.Stage s : problems) {
            boolean hasProblemChild = false;
            for (StageGraph.Stage other : problems) {
                if (other != s && graph.isAncestor(s, other)) {
                    hasProblemChild = true;
                    break;
                }
            }
            if (!hasProblemChild) {
                leaves.add(s);
            }
        }
        leaves.sort(Comparator.comparingLong(s -> {
            FlowNode origin = graph.problemOrigin(s);
            Long t = graph.startMillis(origin != null ? origin : s.start);
            return t != null ? t : Long.MAX_VALUE;
        }));
        List<StageInfo> leafInfos = new ArrayList<>();
        for (StageGraph.Stage s : leaves) {
            leafInfos.add(toInfo(graph, s));
        }

        Result buildResult = run.getResult();
        return new PipelineStagesResponse(
                run.getParent().getFullName(),
                run.getNumber(),
                buildResult != null ? buildResult.toString() : null,
                run.isBuilding(),
                all.size(),
                leafInfos,
                listed);
    }

    @Tool(
            description = "Returns the log of a single Pipeline stage, parallel branch or step, without output from"
                    + " other parallel branches. Output is grouped per step, each starting with a '=====' header."
                    + " By default returns the last 200 lines. Use 'pattern' to grep inside the stage.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    @SuppressFBWarnings(value = "RV_RETURN_VALUE_IGNORED", justification = "we read from offset 0 once")
    public StageLogResponse getStageLog(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @ToolParam(description = "Build number (optional, defaults to the last build)", required = false)
                    Integer buildNumber,
            @ToolParam(
                            description = "What to read: an 'id' or 'problemStepId' from getPipelineStages"
                                    + " (preferred), or a stage name, or a stage path like 'Tests > windows'")
                    String stage,
            @ToolParam(
                            description = "Maximum number of lines to return (default 200, capped by the server)",
                            required = false)
                    Integer maxLines,
            @ToolParam(
                            description = "true (default) returns the last lines, false returns the first lines",
                            required = false)
                    Boolean fromEnd,
            @ToolParam(
                            description = "Optional case-insensitive regex; only matching lines are returned, each"
                                    + " prefixed with its line number in the stage log (e.g. 'L42: ...')",
                            required = false)
                    String pattern) {
        if (stage == null || stage.isBlank()) {
            throw new IllegalArgumentException("'stage' is required: pass an id from getPipelineStages or a stage name");
        }
        WorkflowRun run = resolveRun(jobFullName, buildNumber);
        StageGraph graph = new StageGraph(run);
        FlowNode target = resolveTarget(graph, stage.trim());

        int limit = maxLines == null || maxLines <= 0 ? DEFAULT_MAX_LINES : Math.min(maxLines, HARD_MAX_LINES);
        boolean tail = fromEnd == null || fromEnd;
        Pattern regex = null;
        if (pattern != null && !pattern.isBlank()) {
            try {
                regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid regex in 'pattern': " + e.getDescription());
            }
        }

        // Every node inside the target (or the target itself), ordered as a depth-first walk of the block tree,
        // so each parallel branch's steps stay together.
        List<FlowNode> nodes = new ArrayList<>();
        for (FlowNode n : graph.allNodes) {
            if (n == target || n.getAllEnclosingIds().contains(target.getId())) {
                if (n.getAction(LogAction.class) != null) {
                    nodes.add(n);
                }
            }
        }
        nodes.sort(PipelineStagesExtension::compareTreeOrder);

        LogCollector collector = new LogCollector(run.getCharset(), limit, tail, regex);
        boolean stopped = false;
        for (FlowNode n : nodes) {
            LogAction logAction = n.getAction(LogAction.class);
            if (logAction == null) {
                continue;
            }
            StageGraph.Stage owner = graph.stageOf(n);
            String where = owner != null ? "  (" + graph.path(owner) + ")" : "";
            collector.beginStep("===== [step " + n.getId() + "] " + StageGraph.describeStep(n) + where + " =====");
            try {
                AnnotatedLargeText<? extends FlowNode> text = logAction.getLogText();
                text.writeLogTo(0, collector);
                collector.forceEol();
            } catch (LogCollector.StopReading e) {
                stopped = true;
                break;
            } catch (IOException e) {
                throw new IllegalStateException("Could not read log of step " + n.getId() + ": " + e.getMessage(), e);
            }
        }

        StageGraph.Stage targetStage = graph.stages.get(target.getId());
        String status = targetStage != null ? graph.status(targetStage) : null;
        String name = targetStage != null ? targetStage.name : StageGraph.describeStep(target);
        StageGraph.Stage owner = graph.stageOf(target);
        String path = owner != null ? graph.path(owner) : null;

        String note;
        if (nodes.isEmpty()) {
            note = "No step inside this target wrote any log output.";
        } else if (regex != null) {
            note = collector.isTruncated()
                    ? "More lines matched than returned; narrow the pattern or raise maxLines."
                    : "All matching lines returned.";
        } else if (stopped) {
            note = "Showing the first " + collector.size()
                    + " lines; more exist. Use fromEnd=true, a pattern, or a narrower target (a problemStepId).";
        } else if (collector.isTruncated()) {
            note = "Showing the last " + collector.size() + " of " + collector.getTotalLines()
                    + " lines. Use a pattern or a narrower target (a problemStepId) to see more relevant lines.";
        } else {
            note = "Complete log of this target.";
        }

        return new StageLogResponse(
                run.getParent().getFullName(),
                run.getNumber(),
                target.getId(),
                name,
                path,
                status,
                nodes.size(),
                collector.getTotalLines(),
                regex != null ? collector.getMatchedLines() : null,
                collector.size(),
                collector.isTruncated(),
                note,
                collector.text());
    }

    // ------------------------------------------------------------------ helpers

    private static WorkflowRun resolveRun(String jobFullName, Integer buildNumber) {
        if (jobFullName == null || jobFullName.isBlank()) {
            throw new IllegalArgumentException("jobFullName is required");
        }
        // getItemByFullName returns null when the caller lacks Item.READ, so no data leaks for hidden jobs.
        Job<?, ?> job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job == null) {
            throw new IllegalArgumentException("Job not found (or no permission): " + jobFullName);
        }
        job.checkPermission(Item.READ);
        Run<?, ?> run = buildNumber == null || buildNumber <= 0 ? job.getLastBuild() : job.getBuildByNumber(buildNumber);
        if (run == null) {
            throw new IllegalArgumentException("Build not found: " + jobFullName + " #" + buildNumber);
        }
        if (!(run instanceof WorkflowRun wr)) {
            throw new IllegalArgumentException(
                    jobFullName + " is not a Pipeline job; use getBuildLog / searchBuildLog instead");
        }
        return wr;
    }

    private static FlowNode resolveTarget(StageGraph graph, String stage) {
        FlowNode byId = graph.byId.get(stage);
        if (byId != null) {
            return byId;
        }
        List<StageGraph.Stage> matches = new ArrayList<>();
        for (StageGraph.Stage s : graph.visibleStages()) {
            if (graph.path(s).equals(stage)) {
                return s.start;
            }
            if (s.name.equals(stage)) {
                matches.add(s);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0).start;
        }
        if (matches.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (StageGraph.Stage s : graph.visibleStages()) {
                if (names.size() >= 50) {
                    names.add("...");
                    break;
                }
                names.add(graph.path(s) + " [id " + s.start.getId() + "]");
            }
            throw new IllegalArgumentException(
                    "No stage, branch or step matches '" + stage + "'. Available: " + String.join("; ", names));
        }
        List<String> options = new ArrayList<>();
        for (StageGraph.Stage s : matches) {
            options.add(graph.path(s) + " [id " + s.start.getId() + "]");
        }
        throw new IllegalArgumentException("Stage name '" + stage + "' is ambiguous; pass one of these ids or paths: "
                + String.join("; ", options));
    }

    /** Depth-first order of the block tree: compare outermost-first enclosing ids, then the node's own id. */
    private static int compareTreeOrder(FlowNode a, FlowNode b) {
        List<String> pa = treeKey(a);
        List<String> pb = treeKey(b);
        for (int i = 0; i < Math.min(pa.size(), pb.size()); i++) {
            int c = Long.compare(StageGraph.numericId(pa.get(i)), StageGraph.numericId(pb.get(i)));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(pa.size(), pb.size());
    }

    private static List<String> treeKey(FlowNode n) {
        List<String> key = new ArrayList<>(n.getAllEnclosingIds()); // innermost first
        Collections.reverse(key);
        key.add(n.getId());
        return key;
    }

    private static StageInfo toInfo(StageGraph graph, StageGraph.Stage s) {
        String status = graph.status(s);
        String problem = null;
        String problemStepId = null;
        String problemStep = null;
        if (StageGraph.PROBLEM_STATUSES.contains(status)) {
            FlowNode origin = graph.problemOrigin(s);
            problem = graph.problemMessage(s, origin);
            if (origin != null) {
                problemStepId = origin.getId();
                problemStep = StageGraph.describeStep(origin);
            }
        }
        StageGraph.Stage parent = graph.visibleParent(s);
        return new StageInfo(
                s.start.getId(),
                s.name,
                s.type,
                graph.path(s),
                parent != null ? parent.start.getId() : null,
                status,
                graph.skipReason(s),
                StageGraph.iso(graph.startMillis(s.start)),
                graph.durationMillis(s),
                problem,
                problemStepId,
                problemStep);
    }
}
