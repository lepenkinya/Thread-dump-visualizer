package org.compscicenter.typingFreezeAnalyzer

import java.util.*
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

abstract class RegexLineMatchAction() {
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
    override val pattern = "^on (?<lockName>.*?)@(?<hashCode>[0-9a-fA-F]+)(?<lockOwnerName>.*)".toPattern()
    val lockOwnerNamePattern = "^ owned by \"(?<ownerThreadName>.*?)\"".toPattern()

    fun checkLockOwnerName(str: String, builder: ThreadInfoDigest.Builder) {
        val lockOwnerMatcher = lockOwnerNamePattern.matcher(str)

        if (!lockOwnerMatcher.find()) return

        builder.lockOwnerName(lockOwnerMatcher.group("ownerThreadName"))
    }

    override fun onMatch(matcher: Matcher,
                         dumpBuilder: ThreadDumpInfo.Builder,
                         threadBuilder: ThreadInfoDigest.Builder) {
        threadBuilder.lockName(matcher.group("lockName"))
        checkLockOwnerName(matcher.group("lockOwnerName"), threadBuilder)
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

        val (fileName, lineNumber) = if (fileInfo.contains(':')) {
            val delimiter = fileInfo.indexOf(':')

            with(fileInfo) { substring(0, delimiter) to substring(delimiter + 1, lastIndex).toInt() }
        } else when (fileInfo) {
            "Native Method" -> null to -2
            "Unknown Source" -> null to -1
            else -> fileInfo to -1
        }

        threadBuilder.stackTrace(StackTraceElement(className, methodName, fileName, lineNumber))
    }
}

fun sliceThreadInfos(s: String): ArrayList<ArrayList<String>> {
    val infos = ArrayList<ArrayList<String>>()
    var info = ArrayList<String>()
    var i = 0

    s.lineSequence()
            .filter(String::isNotEmpty)
            .forEach {
                if (it.startsWith('"')) i++
                if (i >= 2) {
                    infos.add(info)
                    info = ArrayList()
                    i = 1
                }

                info.add(it)
            }

    infos.add(info)

    return infos
}

fun String.parseThreadDump(name: String): ThreadDumpInfo {
    val dumpBuilder = ThreadDumpInfo.Builder().name(name)
    val threadListString = sliceThreadInfos(this)

    threadListString.forEach { threadInfoString ->
        val threadBuilder = ThreadInfoDigest.Builder()

        threadInfoString.forEach { line ->
            LineMatchAction.list.find { it.tryMatch(line.trim(), dumpBuilder, threadBuilder) } ?: throw IllegalStateException("$line not handled")
        }

        dumpBuilder.threadInfo(threadBuilder.build())
    }

    return dumpBuilder.build()
}