# MCP Pipeline Stages

A small Jenkins plugin that adds four read-only tools to the official
[MCP Server plugin](https://plugins.jenkins.io/mcp-server/). They let an MCP client find the failing
stage or parallel branch and read **only that part of the log**, and look at failing tests **one at a
time** with size limits, instead of pulling the full console log or every test's stack trace and output.

| Tool | What it returns |
|---|---|
| `getPipelineStages` | Every stage and parallel branch with status, path (`Tests > windows`), start time, duration, and for problems the error message plus the step that caused it. `failedLeafStages` lists the innermost failing stages, earliest first. `onlyProblems=true` trims the list for large pipelines. |
| `getStageLog` | The log of one stage, branch or step. Output is grouped per step under `===== [step N] sh ... (path) =====` headers, so parallel output is never interleaved. Last 200 lines by default; supports `maxLines`, `fromEnd`, and a case-insensitive `pattern` (grep within the stage). |
| `getFailedTestsSummary` | A compact list of failing tests: name, the stage it ran in (same path format as `getPipelineStages`), whether it is new in this build, how long it has been failing, and the first line of the message. New failures first. Optional `filter` (regex on the test name), `stage`, `maxTests`. |
| `getTestCaseDetails` | One test's failure message, stack trace, stdout and stderr, each capped at `maxChars` (default 4000). Stack traces keep their top, stdout/stderr their end. Optional `pattern` greps all four parts. |

The test tools need the JUnit plugin and appear only when it is installed; the stage tools work without it.

`stage` in `getStageLog` accepts an `id` or `problemStepId` from `getPipelineStages` (most precise), a stage path such as
`Tests > windows`, or a stage name if it is unique.

## How it works

It walks the Pipeline flow graph (the same data the Pipeline Graph View uses) instead of parsing console text:

- a **stage** is a block whose body carries a label; a **parallel branch** is a block labelled with a thread name;
- Declarative's wrapper branch around each parallel stage (same name) is collapsed into one entry;
- **status** comes from the block's end node (error, abort/timeout via `FlowInterruptedException`), Declarative's
  `STAGE_STATUS` tags (skipped, failed-and-continued), and `WarningAction`s (`unstable`, `catchError`);
- **logs** are read per step from each node's own log, which is why they contain no output from other branches;
- **tests** are mapped to stages through the flow node the JUnit plugin records for each report.

## Build

Requires JDK 17+ and Maven 3.9+, with access to `repo.jenkins-ci.org`.

1. Rename `groupId` in `pom.xml` and the Java package (`com.example.jenkins.mcp.stages`) to your organisation.
2. Set `mcp-server.version` in `pom.xml` to the MCP Server version installed on your controller
   (Manage Jenkins → Plugins → Installed).
3. Build and run the test (it starts an embedded Jenkins and runs a parallel Pipeline):

   ```
   mvn clean verify
   ```

   The plugin is written to `target/mcp-pipeline-stages.hpi`. To try it locally first: `mvn hpi:run`.

If the enforcer reports dependency version conflicts, align `jenkins.baseline` and the BOM version with your
controller's Jenkins LTS line.

## Install

1. Manage Jenkins → Plugins → Advanced settings → Deploy Plugin → upload `mcp-pipeline-stages.hpi`.
2. Restart Jenkins (the MCP server registers tools at startup).
3. Reconnect your MCP client and check that it now lists `getPipelineStages`, `getStageLog`, `getFailedTestsSummary`
   and `getTestCaseDetails`.

## Security

Both tools are read-only (`readOnlyHint=true`). Jobs are looked up with the caller's own permissions and
`Item.READ` is checked, so a token only sees jobs its user can see. Nothing is triggered or modified.

## Tuning and limits

- Hard cap on returned lines: `-Dcom.example.jenkins.mcp.stages.PipelineStagesExtension.maxLines=5000` (default 5000;
  the property name follows your package if you rename it).
- Hard cap on characters per test part: `-Dcom.example.jenkins.mcp.stages.TestResultsExtension.maxChars=50000`.
- Test stdout/stderr exist only if the JUnit XML report contains them. Jenkins keeps output of failing tests but
  trims very long output, and usually discards output of passing tests.
- Pipelines with no `stage` blocks have no stages to list; use `getBuildLog` / `searchBuildLog` for those.
- Step logs contain step output only, not the `[Pipeline]` marker lines of the full console.
- For running builds, results reflect the flow graph so far (`IN_PROGRESS` stages).
- Each call loads the build's flow graph. This is fast for typical builds; very large graphs (tens of thousands of
  steps) take longer per call.
