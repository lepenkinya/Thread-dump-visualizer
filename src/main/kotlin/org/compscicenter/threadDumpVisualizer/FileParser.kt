package org.compscicenter.threadDumpVisualizer

import java.util.regex.Matcher
import java.util.regex.Pattern

object LineMatchAction {
    val list = listOf(ThreadNameMatchAction,
            LockMatchAction,
            TreadStateMatchAction,
            InNativeMatchAction,
            SuspendedMatchAction,
            StackTraceElementMatchAction)
}

abstract class RegexLineMatchAction {
    abstract val pattern: Pattern
    abstract fun onMatch(matcher: Matcher,
                         dumpBuilder: ThreadDumpInfo.Builder,
                         threadBuilder: ThreadInfoDigest.Builder)

    fun tryMatch(s: String,
                 dumpBuilder: ThreadDumpInfo.Builder,
                 threadBuilder: ThreadInfoDigest.Builder): Boolean {
        val matcher = pattern.matcher(s)

        if (matcher.find()) {
            onMatch(matcher, dumpBuilder, threadBuilder)
            return true
        }

        return false
    }
}

object ThreadNameMatchAction : RegexLineMatchAction() {
    override val pattern = "^\"(?<threadName>.*)\"".toPattern()
    val awtPattern = "^AWT-EventQueue.*?(?<version>[\\d.]+)#(?<buildNumber>.*?) (?<product>.*?), eap:(?<eap>.*?).*".toPattern()

    fun checkAWT(threadName: String, dumpBuilder: ThreadDumpInfo.Builder) {
        val awtMatcher = awtPattern.matcher(threadName)

        if (!awtMatcher.find()) return

        dumpBuilder.buildNumber(awtMatcher.group("buildNumber"))
                .version(awtMatcher.group("version"))
                .product(awtMatcher.group("product"))
    }

    override fun onMatch(matcher: Matcher,
                         dumpBuilder: ThreadDumpInfo.Builder,
                         threadBuilder: ThreadInfoDigest.Builder) {
        val threadName = matcher.group("threadName")

        threadBuilder.threadName(threadName)
        checkAWT(threadName, dumpBuilder)
    }
}

object TreadStateMatchAction : RegexLineMatchAction() {
    override val pattern = "^java.lang.Thread.State: (?<threadState>.*)".toPattern()

    override fun onMatch(matcher: Matcher,
                         dumpBuilder: ThreadDumpInfo.Builder,
                         threadBuilder: ThreadInfoDigest.Builder) {
        val threadStateString = matcher.group("threadState")
        val state = Thread.State.valueOf(threadStateString)

        threadBuilder.threadState(state)
    }
}

object InNativeMatchAction : RegexLineMatchAction() {
    override val pattern = "(in native)".toPattern()

    override fun onMatch(matcher: Matcher,
                         dumpBuilder: ThreadDumpInfo.Builder,
                         threadBuilder: ThreadInfoDigest.Builder) {
        threadBuilder.inNative(true)
    }
}

object SuspendedMatchAction : RegexLineMatchAction() {
    override val pattern = "(suspended)".toPattern()

    override fun onMatch(matcher: Matcher,
                         dumpBuilder: ThreadDumpInfo.Builder,
                         threadBuilder: ThreadInfoDigest.Builder) {
        threadBuilder.suspended(true)
    }
}

object LockMatchAction : RegexLineMatchAction() {
    override val pattern = "^on (?<lockName>[\\p{L}0-9\\.\\_\\$]+)(@(?<hashCode>[0-9a-fA-F]+))?( owned by \"(?<ownerThreadName>.*?)\".*)?".toPattern()

    override fun onMatch(matcher: Matcher,
                         dumpBuilder: ThreadDumpInfo.Builder,
                         threadBuilder: ThreadInfoDigest.Builder) {
        val lockName = matcher.group("lockName")
        val ownerThreadName = matcher.group("ownerThreadName")

        threadBuilder.lockName(lockName)
        if (ownerThreadName != null) threadBuilder.lockOwnerName(ownerThreadName)
    }
}

object StackTraceElementMatchAction : RegexLineMatchAction() {
    override val pattern = "^at (?<entryPoint>.*?)\\((?<fileInfo>.*?)\\)".toPattern()

    override fun onMatch(matcher: Matcher,
                         dumpBuilder: ThreadDumpInfo.Builder,
                         threadBuilder: ThreadInfoDigest.Builder) {
        val entryPoint = matcher.group("entryPoint")
        val fileInfo = matcher.group("fileInfo")
        val dotIndex = entryPoint.lastIndexOf('.')
        val className = entryPoint.substring(0, dotIndex)
        val methodName = entryPoint.substring(dotIndex + 1, entryPoint.length)
        val delimiterIdx = fileInfo.indexOf(':')
        val (fileName, lineNumber) = if (delimiterIdx != -1) {
            with(fileInfo) { substring(0, delimiterIdx) to substring(delimiterIdx + 1, length).toInt() }
        } else when (fileInfo) {
            "Native Method" -> null to -2
            "Unknown Source" -> null to -1
            else -> fileInfo to -1
        }

        val stackTraceElement = StackTraceElement(className, methodName, fileName, lineNumber)
        threadBuilder.stackTrace(stackTraceElement)
    }
}

fun String.fireAction(dumpBuilder: ThreadDumpInfo.Builder,
                      threadBuilder: ThreadInfoDigest.Builder): Boolean {
    val trimmed = trim()
    return LineMatchAction.list.any { it.tryMatch(trimmed, dumpBuilder, threadBuilder) }
}

fun String.parseThreadDump(name: String): ThreadDumpInfo {
    val dumpBuilder = ThreadDumpInfo.Builder().name(name)
    var threadBuilder = ThreadInfoDigest.Builder()
    var threadInfoStarted = false

    lineSequence().filter(String::isNotEmpty).forEach { line ->
        if (line.startsWith('"')) {
            if (threadInfoStarted) {
                dumpBuilder.threadInfo(threadBuilder.build())
                threadBuilder = ThreadInfoDigest.Builder()
            } else {
                threadInfoStarted = true
            }
        }

        if (!line.fireAction(dumpBuilder, threadBuilder)) throw IllegalStateException("line: \"$line\" not parsed")
    }

    if (threadInfoStarted) dumpBuilder.threadInfo(threadBuilder.build())

    return dumpBuilder.build()
}