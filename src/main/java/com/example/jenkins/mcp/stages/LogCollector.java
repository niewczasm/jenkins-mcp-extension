package com.example.jenkins.mcp.stages;

import hudson.console.ConsoleNote;
import hudson.console.LineTransformationOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.regex.Pattern;

/**
 * Collects log lines from one or more step logs while keeping memory bounded.
 *
 * <ul>
 *   <li>tail mode keeps only the last {@code maxLines} lines;</li>
 *   <li>head mode stops reading (via {@link StopReading}) once {@code maxLines} lines are collected;</li>
 *   <li>with a pattern, only matching lines are kept, prefixed with their line number in the stage log.</li>
 * </ul>
 */
final class LogCollector extends LineTransformationOutputStream {

    /** Thrown to abort reading once head mode has enough lines. */
    static final class StopReading extends RuntimeException {
        StopReading() {
            super(null, null, false, false);
        }
    }

    private static final int MAX_LINE_CHARS = 2000;

    private final Charset charset;
    private final int maxLines;
    private final boolean fromEnd;
    private final Pattern pattern;
    /** A kept line plus the header of the step it came from (headers do not count against maxLines). */
    private record Entry(String header, String line) {}

    private final ArrayDeque<Entry> lines = new ArrayDeque<>();

    private String currentHeader;
    private long totalLines;
    private long matchedLines;
    private boolean truncated;

    LogCollector(Charset charset, int maxLines, boolean fromEnd, Pattern pattern) {
        this.charset = charset;
        this.maxLines = maxLines;
        this.fromEnd = fromEnd;
        this.pattern = pattern;
    }

    /** Header for the following lines; printed once before the first kept line of each step. */
    void beginStep(String header) {
        this.currentHeader = header;
    }

    @Override
    protected void eol(byte[] b, int len) {
        String line = new String(b, 0, len, charset);
        line = ConsoleNote.removeNotes(line).stripTrailing();
        totalLines++;
        if (pattern != null) {
            if (!pattern.matcher(line).find()) {
                return;
            }
            matchedLines++;
            line = "L" + totalLines + ": " + line;
        }
        if (line.length() > MAX_LINE_CHARS) {
            line = line.substring(0, MAX_LINE_CHARS) + " ...[line truncated]";
        }
        keep(new Entry(currentHeader, line));
    }

    private void keep(Entry line) {
        if (fromEnd) {
            lines.addLast(line);
            if (lines.size() > maxLines) {
                lines.removeFirst();
                truncated = true;
            }
        } else {
            if (lines.size() >= maxLines) {
                truncated = true;
                throw new StopReading();
            }
            lines.addLast(line);
        }
    }

    long getTotalLines() {
        return totalLines;
    }

    long getMatchedLines() {
        return matchedLines;
    }

    boolean isTruncated() {
        return truncated;
    }

    int size() {
        return lines.size();
    }

    String text() {
        StringBuilder sb = new StringBuilder();
        String lastHeader = null;
        for (Entry e : lines) {
            if (e.header() != null && !e.header().equals(lastHeader)) {
                sb.append(e.header()).append('\n');
                lastHeader = e.header();
            }
            sb.append(e.line()).append('\n');
        }
        return sb.toString();
    }
}
