package com.example.jenkins.mcp.stages;

import hudson.model.Result;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.TagsAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;

/**
 * Builds the stage / parallel-branch tree of one Pipeline run from its flow graph.
 *
 * <p>Detection rules (same as Pipeline Graph View / Blue Ocean):
 * a stage is a block start node with a {@link LabelAction} (the stage body);
 * a parallel branch is a block start node whose label is also a {@link ThreadNameAction}.
 */
final class StageGraph {

    static final String STAGE = "STAGE";
    static final String BRANCH = "PARALLEL_BRANCH";

    /** Statuses that count as "something went wrong here". */
    static final Set<String> PROBLEM_STATUSES = Set.of("FAILURE", "UNSTABLE", "ABORTED", "NOT_COMPLETED");

    private static final String STAGE_STATUS_TAG = "STAGE_STATUS";
    private static final int MAX_STEP_ARGS = 300;

    /** Orders flow node ids numerically ("2" < "10"). */
    static final Comparator<FlowNode> BY_ID = Comparator.comparingLong(n -> numericId(n.getId()));

    final WorkflowRun run;
    final FlowExecution execution;
    final List<FlowNode> allNodes;
    final Map<String, FlowNode> byId = new HashMap<>();
    private final Map<String, BlockEndNode<?>> endByStartId = new HashMap<>();
    /** All stages and branches, keyed by start node id, in id order. */
    final Map<String, Stage> stages = new LinkedHashMap<>();

    static final class Stage {
        final FlowNode start;
        final String type;
        final String name;
        Stage parent;
        final List<Stage> children = new ArrayList<>();
        /** Every flow node nested inside this block (any depth), in id order. */
        final List<FlowNode> contained = new ArrayList<>();
        /**
         * Declarative wraps each parallel stage in a branch of the same name; we hide that branch so the
         * model sees one entry per stage instead of two.
         */
        boolean hidden;

        Stage(FlowNode start, String type, String name) {
            this.start = start;
            this.type = type;
            this.name = name;
        }
    }

    StageGraph(WorkflowRun run) {
        this.run = run;
        this.execution = run.getExecution();
        if (execution == null) {
            throw new IllegalArgumentException("Build " + run.getFullDisplayName()
                    + " has no Pipeline execution (it never started or its flow graph could not be loaded)");
        }
        allNodes = new ArrayList<>(new DepthFirstScanner().allNodes(execution));
        allNodes.sort(BY_ID);

        for (FlowNode n : allNodes) {
            byId.put(n.getId(), n);
            if (n instanceof BlockEndNode<?> end) {
                try {
                    endByStartId.put(end.getStartNode().getId(), end);
                } catch (RuntimeException ignored) {
                    // corrupt / partially loaded graph: treat block as not finished
                }
            }
        }

        for (FlowNode n : allNodes) {
            if (!(n instanceof BlockStartNode)) {
                continue;
            }
            LabelAction label = n.getPersistentAction(LabelAction.class);
            if (label == null) {
                continue;
            }
            ThreadNameAction thread = n.getPersistentAction(ThreadNameAction.class);
            if (thread != null) {
                stages.put(n.getId(), new Stage(n, BRANCH, thread.getThreadName()));
            } else {
                stages.put(n.getId(), new Stage(n, STAGE, label.getDisplayName()));
            }
        }

        for (Stage s : stages.values()) {
            for (BlockStartNode enclosing : s.start.getEnclosingBlocks()) { // innermost first
                Stage p = stages.get(enclosing.getId());
                if (p != null) {
                    s.parent = p;
                    p.children.add(s);
                    break;
                }
            }
        }

        for (FlowNode n : allNodes) {
            for (String id : n.getAllEnclosingIds()) {
                Stage s = stages.get(id);
                if (s != null) {
                    s.contained.add(n);
                }
            }
        }

        for (Stage s : stages.values()) {
            if (BRANCH.equals(s.type)
                    && s.children.size() == 1
                    && STAGE.equals(s.children.get(0).type)
                    && s.children.get(0).name.equals(s.name)) {
                s.hidden = true;
            }
        }
    }

    // ---------------------------------------------------------------- tree helpers

    List<Stage> visibleStages() {
        List<Stage> out = new ArrayList<>();
        for (Stage s : stages.values()) {
            if (!s.hidden) {
                out.add(s);
            }
        }
        return out;
    }

    Stage visibleParent(Stage s) {
        Stage p = s.parent;
        while (p != null && p.hidden) {
            p = p.parent;
        }
        return p;
    }

    String path(Stage s) {
        List<String> names = new ArrayList<>();
        for (Stage cur = s; cur != null; cur = visibleParent(cur)) {
            if (!cur.hidden) {
                names.add(cur.name);
            }
        }
        Collections.reverse(names);
        return String.join(" > ", names);
    }

    /** Nearest visible stage or branch enclosing an arbitrary node (or the node itself if it is one). */
    Stage stageOf(FlowNode n) {
        Stage self = stages.get(n.getId());
        if (self != null && !self.hidden) {
            return self;
        }
        for (String id : n.getAllEnclosingIds()) {
            Stage s = stages.get(id);
            if (s != null && !s.hidden) {
                return s;
            }
        }
        return null;
    }

    boolean isAncestor(Stage ancestor, Stage s) {
        for (Stage cur = s.parent; cur != null; cur = cur.parent) {
            if (cur == ancestor) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- status

    String stageTag(Stage s) {
        // Declarative tags either the stage body node or the outer stage step node.
        String v = TagsAction.getTagValue(s.start, STAGE_STATUS_TAG);
        if (v == null && !s.start.getParents().isEmpty()) {
            v = TagsAction.getTagValue(s.start.getParents().get(0), STAGE_STATUS_TAG);
        }
        return v;
    }

    String status(Stage s) {
        String tag = stageTag(s);
        if (tag != null && tag.startsWith("SKIPPED")) {
            return "SKIPPED";
        }
        BlockEndNode<?> end = endByStartId.get(s.start.getId());
        if (end == null) {
            return run.isBuilding() ? "IN_PROGRESS" : "NOT_COMPLETED";
        }
        ErrorAction error = end.getError();
        if (error != null) {
            if (error.getError() instanceof FlowInterruptedException fie) {
                return fie.getResult().toString();
            }
            return "FAILURE";
        }
        if ("FAILED_AND_CONTINUED".equals(tag)) {
            return "FAILURE";
        }
        Result worst = null;
        for (FlowNode n : s.contained) {
            WarningAction w = n.getPersistentAction(WarningAction.class);
            if (w != null && (worst == null || w.getResult().isWorseThan(worst))) {
                worst = w.getResult();
            }
        }
        return worst != null ? worst.toString() : "SUCCESS";
    }

    /** Human-readable reason for a skipped stage, or null. */
    String skipReason(Stage s) {
        String tag = stageTag(s);
        return tag != null && tag.startsWith("SKIPPED") ? tag : null;
    }

    /**
     * The node that caused this block to fail or become unstable: the earliest step inside it carrying an
     * error, falling back to the earliest failed inner block, then to the earliest warning (unstable).
     */
    FlowNode problemOrigin(Stage s) {
        for (FlowNode n : s.contained) {
            if (!(n instanceof BlockEndNode) && n.getError() != null) {
                return n;
            }
        }
        for (FlowNode n : s.contained) {
            if (n instanceof BlockEndNode<?> end && end.getError() != null) {
                try {
                    return end.getStartNode();
                } catch (RuntimeException e) {
                    return end;
                }
            }
        }
        for (FlowNode n : s.contained) {
            if (n.getPersistentAction(WarningAction.class) != null) {
                return n;
            }
        }
        return null;
    }

    String problemMessage(Stage s, FlowNode origin) {
        ErrorAction err = null;
        if (origin != null) {
            err = origin.getError();
            if (err == null) {
                BlockEndNode<?> end = endByStartId.get(origin.getId());
                err = end != null ? end.getError() : null;
            }
        }
        if (err == null) {
            BlockEndNode<?> end = endByStartId.get(s.start.getId());
            err = end != null ? end.getError() : null;
        }
        if (err != null) {
            Throwable t = err.getError();
            String msg = t.getMessage();
            if (t instanceof FlowInterruptedException fie && !fie.getCauses().isEmpty()) {
                msg = fie.getCauses().get(0).getShortDescription();
            }
            return t.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
        }
        if (origin != null) {
            WarningAction w = origin.getPersistentAction(WarningAction.class);
            if (w != null) {
                return w.getResult() + (w.getMessage() != null ? ": " + w.getMessage() : "");
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- timing and descriptions

    Long startMillis(FlowNode n) {
        long t = TimingAction.getStartTime(n);
        return t > 0 ? t : null;
    }

    Long durationMillis(Stage s) {
        Long start = startMillis(s.start);
        if (start == null) {
            return null;
        }
        BlockEndNode<?> end = endByStartId.get(s.start.getId());
        Long endTime = end != null ? startMillis(end) : (run.isBuilding() ? System.currentTimeMillis() : null);
        return endTime != null ? Math.max(0, endTime - start) : null;
    }

    static String iso(Long millis) {
        return millis != null ? Instant.ofEpochMilli(millis).toString() : null;
    }

    static String describeStep(FlowNode n) {
        String fn = n.getDisplayFunctionName();
        String args = null;
        try {
            args = ArgumentsAction.getStepArgumentsAsString(n);
        } catch (RuntimeException ignored) {
            // arguments are best effort
        }
        if (args == null || args.isBlank()) {
            return fn;
        }
        args = args.replaceAll("\\s+", " ").trim();
        if (args.length() > MAX_STEP_ARGS) {
            args = args.substring(0, MAX_STEP_ARGS) + "...";
        }
        return fn + " " + args;
    }

    static long numericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
