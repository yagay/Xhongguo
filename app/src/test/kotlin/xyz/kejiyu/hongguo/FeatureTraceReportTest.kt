package xyz.kejiyu.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureTraceReportTest {
    @Test fun deduplicatesHostLogAndLogcatByProcessAndSequence() {
        val line = "XH_TRACE feature=media stage=resolution result=ok flow=5 seq=6 pid=98 monoMs=100"
        val text = "[01:00] [INFO] $line\n" +
            "11-11 10:00 I/ZongHe: $line\n" +
            "XH_TRACE feature=artwork stage=decode result=fail flow=5 seq=7 pid=98 monoMs=200"
        val report = FeatureTraceReport.analyze(text)
        assertEquals(2, report.parsedEvents)
        assertTrue(report.summary.contains("media: events=1"))
        assertTrue(report.summary.contains("artwork: events=1 problemSignals=1"))
        assertTrue(report.errors.contains("stage=decode result=fail"))
    }

    @Test fun emptyEvidenceDoesNotPretendSuccess() {
        val report = FeatureTraceReport.analyze("No module or Root logs")
        assertEquals(0, report.parsedEvents)
        assertTrue(report.summary.contains("not-observed"))
        assertTrue(report.events.contains("No structured feature events"))
    }

    @Test fun serializesIndependentFlowsWithoutMixingThem() {
        val text = "XH_TRACE feature=media stage=flow_start result=observed flow=12 seq=1 pid=7\n" +
            "XH_TRACE feature=media stage=flow_start result=observed flow=13 seq=2 pid=7"
        val report = FeatureTraceReport.analyze(text)
        assertEquals(2, report.parsedEvents)
        assertTrue(report.events.contains("flow=12"))
        assertTrue(report.events.contains("flow=13"))
    }
}
