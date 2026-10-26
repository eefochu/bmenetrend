package hu.app.bmenetrend.map

/** Trace Android callers, keeping map algorithms usable by plain JVM regression tests. */
internal inline fun <T> traceMapWork(name: String,block: ()->T): T {
    android.os.Trace.beginSection(name)
    return try { block() } finally { android.os.Trace.endSection() }
}
