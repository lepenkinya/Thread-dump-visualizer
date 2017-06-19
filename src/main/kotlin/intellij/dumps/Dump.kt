package intellij.dumps

class ThreadInfo(
        val threadName: String,
        val lockName: String?,
        val lockOwnerName: String?,
        val inNative: Boolean,
        val suspended: Boolean,
        val threadState: Thread.State,
        val stackTrace: List<StackTraceElement>?
)

class ApplicationInfo(val product: String, val version: String, val jreVersion: String) {
    companion object {
        val EMPTY = ApplicationInfo("", "", "")
    }
}

class Dump(val info: ApplicationInfo, val threads: List<ThreadInfo>)