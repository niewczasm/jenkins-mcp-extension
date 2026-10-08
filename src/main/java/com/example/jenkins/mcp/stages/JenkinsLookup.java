package com.example.jenkins.mcp.stages;

import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

/** Shared job/build lookup that respects the caller's permissions. */
final class JenkinsLookup {

    private JenkinsLookup() {}

    static Run<?, ?> resolveRun(String jobFullName, Integer buildNumber) {
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
        return run;
    }

    static WorkflowRun resolveWorkflowRun(String jobFullName, Integer buildNumber) {
        Run<?, ?> run = resolveRun(jobFullName, buildNumber);
        if (!(run instanceof WorkflowRun wr)) {
            throw new IllegalArgumentException(
                    jobFullName + " is not a Pipeline job; use getBuildLog / searchBuildLog instead");
        }
        return wr;
    }
}
