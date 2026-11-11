package xyz.kejiyu.hongguo

/**
 * Extract a self-contained feature timeline from the existing host logs.
 * Do not infer success from a setting or from hook registration.
 */
object FeatureTraceReport {
    data class Report(
        val summary: String,
        val events: String,
        val errors: String,
        val parsedEvents: Int
    )

    fun analyze(evidence: String): Report {
        val entries = ArrayList<String>()
        val unique = HashSet<String>()
        for (line in evidence.lineSequence()) {
            val start = line.indexOf("XH_TRACE feature=")
            if (start < 0) continue
            val entry = line.substring(start).trim().take(500)
            val pid = field(entry, "pid")
            val seq = field(entry, "seq")
            val identity = if (pid != null && seq != null)
                pid + ":" + seq else entry
            if (unique.add(identity)) entries.add(entry)
        }
        val groups = entries.groupBy { field(it, "feature") ?: "unknown" }
        val faults = entries.filter {
            field(it, "result") in setOf("fail", "error", "degraded")
        }
        val header = buildString {
            appendLine("Xhongguo beta feature trace")
            appendLine("================================")
            appendLine("Structured event count: " + entries.size)
            appendLine("Error/degraded event count: " + faults.size)
            appendLine("Notes: this is observed evidence, not an automatic success verdict.")
            appendLine("No events may mean disabled feature, no reproduction or missing Root logs.")
            appendLine()
            for (category in FeatureTraceCatalog.categories) {
                val group = groups[category].orEmpty()
                val failures = group.count {
                    field(it, "result") in setOf("fail", "error", "degraded")
                }
                val last = group.lastOrNull()?.let {
                    val stage = field(it, "stage").orEmpty()
                    val result = field(it, "result").orEmpty()
                    "$stage/$result"
                } ?: "not-observed"
                appendLine("$category: events=${group.size} problemSignals=$failures last=$last")
            }
        }
        return Report(
            summary = header,
            events = if (entries.isEmpty()) "No structured feature events collected."
                else entries.takeLast(1400).joinToString("\n"),
            errors = if (faults.isEmpty()) "No structured failure/degradation events observed."
                else faults.takeLast(200).joinToString("\n"),
            parsedEvents = entries.size
        )
    }

    private fun field(line: String, name: String): String? =
        Regex("(?:^|\\s)" + Regex.escape(name) + "=([^\\s]+)")
            .find(line)?.groupValues?.getOrNull(1)
}
