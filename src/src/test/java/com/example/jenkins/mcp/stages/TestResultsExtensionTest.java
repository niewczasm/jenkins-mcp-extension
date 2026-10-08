package com.example.jenkins.mcp.stages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
class TestResultsExtensionTest {

    private static final String SCRIPT = """
            def pass = '''<testsuite name="com.example.CalcTest" tests="2" failures="0">
              <testcase classname="com.example.CalcTest" name="adds" time="0.01"/>
              <testcase classname="com.example.CalcTest" name="divides" time="0.02"/>
            </testsuite>'''
            def fail = '''<testsuite name="com.example.CalcTest" tests="2" failures="1">
              <testcase classname="com.example.CalcTest" name="adds" time="0.01"/>
              <testcase classname="com.example.CalcTest" name="divides" time="0.02">
                <failure message="expected 2 but was 3" type="java.lang.AssertionError">java.lang.AssertionError: expected 2 but was 3
                at com.example.CalcTest.divides(CalcTest.java:42)</failure>
                <system-out>computing 6/3
            result=3</system-out>
                <system-err>warning: rounding</system-err>
              </testcase>
            </testsuite>'''
            node {
              stage('Tests') {
                parallel(
                  linux: { stage('linux') { writeFile file: 'linux.xml', text: pass; junit 'linux.xml' } },
                  windows: { stage('windows') { writeFile file: 'windows.xml', text: fail; junit 'windows.xml' } }
                )
              }
            }
            """;

    @Test
    void summarisesAndDetailsFailingTests(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(SCRIPT, true));
        j.buildAndAssertStatus(Result.UNSTABLE, p);

        TestResultsExtension ext = ExtensionList.lookupSingleton(TestResultsExtension.class);

        var summary = ext.getFailedTestsSummary("p", null, null, null, null);
        assertEquals(1, summary.failCount());
        assertEquals(1, summary.newFailureCount());
        assertEquals(1, summary.tests().size());
        var failed = summary.tests().get(0);
        assertEquals("com.example.CalcTest.divides", failed.test());
        assertEquals("Tests > windows", failed.stage());
        assertTrue(failed.newInThisBuild());
        assertTrue(failed.message().contains("expected 2 but was 3"), failed.message());

        assertEquals(0, ext.getFailedTestsSummary("p", null, null, null, "Tests > linux").tests().size());
        assertEquals(1, ext.getFailedTestsSummary("p", null, null, "divid", "Tests").tests().size());
        assertEquals(0, ext.getFailedTestsSummary("p", null, null, "nomatch", null).tests().size());

        var details = ext.getTestCaseDetails("p", null, failed.test(), null, null, null);
        assertEquals("Tests > windows", details.stage());
        assertTrue(details.errorDetails().text().contains("expected 2 but was 3"));
        assertTrue(details.stackTrace().text().contains("CalcTest.java:42"));
        assertTrue(details.stdout().text().contains("result=3"));
        assertTrue(details.stderr().text().contains("rounding"));
        assertFalse(details.stdoutIsSuiteLevel());

        var grep = ext.getTestCaseDetails("p", null, failed.test(), null, null, "result");
        assertTrue(grep.stdout().text().contains("L2: result=3"), grep.stdout().text());
        assertEquals("", grep.stderr().text());

        var cut = ext.getTestCaseDetails("p", null, failed.test(), null, 5, null);
        assertTrue(cut.stdout().truncated());
        assertTrue(cut.stdout().text().endsWith("ult=3"), cut.stdout().text());

        // A passing test that ran in both stages is ambiguous until the stage is given.
        assertThrows(IllegalArgumentException.class,
                () -> ext.getTestCaseDetails("p", null, "com.example.CalcTest.adds", null, null, null));
        var adds = ext.getTestCaseDetails("p", null, "com.example.CalcTest.adds", "Tests > linux", null, null);
        assertEquals("PASSED", adds.status());

        assertThrows(IllegalArgumentException.class,
                () -> ext.getTestCaseDetails("p", null, "nope", null, null, null));
    }
}
