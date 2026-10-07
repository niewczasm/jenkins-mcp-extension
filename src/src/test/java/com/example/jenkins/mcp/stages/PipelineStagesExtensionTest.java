package com.example.jenkins.mcp.stages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.ExtensionList;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PipelineStagesExtensionTest {

    private static final String SCRIPT = """
            stage('Build') { echo 'building' }
            stage('Tests') {
              parallel(
                linux: { stage('linux') { for (int i = 0; i < 5; i++) { echo "linux says hi ${i}" } } },
                windows: { stage('windows') { echo 'windows says hi'; error 'boom on windows' } }
              )
            }
            stage('Deploy') { echo 'never reached' }
            """;

    @Test
    void findsFailingParallelBranchAndReadsOnlyItsLog(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(SCRIPT, true));
        j.buildAndAssertStatus(Result.FAILURE, p);

        PipelineStagesExtension ext = ExtensionList.lookupSingleton(PipelineStagesExtension.class);

        var stages = ext.getPipelineStages("p", null, null);
        assertEquals("FAILURE", stages.buildResult());
        // Declarative-style wrapper branches with the same name as their stage are collapsed.
        assertTrue(stages.stages().stream().noneMatch(s -> StageGraph.BRANCH.equals(s.type())));

        var build = stages.stages().stream().filter(s -> s.name().equals("Build")).findFirst().orElseThrow();
        assertEquals("SUCCESS", build.status());
        var linux = stages.stages().stream().filter(s -> s.name().equals("linux")).findFirst().orElseThrow();
        assertEquals("SUCCESS", linux.status());
        assertEquals("Tests > linux", linux.path());

        assertEquals(1, stages.failedLeafStages().size());
        var windows = stages.failedLeafStages().get(0);
        assertEquals("windows", windows.name());
        assertEquals("FAILURE", windows.status());
        assertTrue(windows.problem().contains("boom on windows"), windows.problem());
        assertNotNull(windows.problemStepId());

        var onlyProblems = ext.getPipelineStages("p", null, true);
        assertTrue(onlyProblems.stages().stream().noneMatch(s -> s.name().equals("linux")));

        var log = ext.getStageLog("p", null, windows.id(), null, null, null);
        assertTrue(log.log().contains("windows says hi"), log.log());
        assertFalse(log.log().contains("linux says hi"), log.log());

        var byPath = ext.getStageLog("p", null, "Tests > linux", 2, true, null);
        assertEquals(2, byPath.returnedLines());
        assertTrue(byPath.truncated());
        assertTrue(byPath.log().contains("linux says hi 4"), byPath.log());
        assertFalse(byPath.log().contains("linux says hi 2"), byPath.log());
        assertTrue(byPath.log().startsWith("===== [step "), byPath.log()); // header survives truncation

        var grep = ext.getStageLog("p", null, "Tests", null, null, "HI 3");
        assertEquals(1L, grep.matchedLines());
        assertTrue(grep.log().contains("linux says hi 3"), grep.log());

        assertThrows(IllegalArgumentException.class, () -> ext.getStageLog("p", null, "nope", null, null, null));
    }
}
