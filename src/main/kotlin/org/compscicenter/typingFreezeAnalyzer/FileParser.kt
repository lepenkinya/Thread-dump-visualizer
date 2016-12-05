package org.compscicenter.typingFreezeAnalyzer

import org.bson.types.ObjectId
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
    abstract fun onMatch(matcher: Matcher, threadDumpInfo: ThreadDumpInfo, builder: ThreadInfoDigest.Builder)

    fun tryMatch(s: String, threadDumpInfo: ThreadDumpInfo, threadInfo: ThreadInfoDigest.Builder): Boolean {
        val matcher = pattern.matcher(s)

        if (matcher.find()) {
            onMatch(matcher, threadDumpInfo, threadInfo)
            return true
        }

        return false
    }
}

object ThreadNameMatchAction : RegexLineMatchAction() {
    override val pattern = "^\"(?<threadName>.*)\"".toPattern()
    val awtPattern = "^AWT-EventQueue.*?(?<version>[\\d.]+)#(?<buildNumber>.*?) (?<product>.*?), eap:(?<eap>.*?).*".toPattern()

    fun checkAWT(threadName: String, threadDumpInfo: ThreadDumpInfo) {
        val awtMatcher = awtPattern.matcher(threadName)

        if (!awtMatcher.find()) return

        with(threadDumpInfo) {
            buildNumber = awtMatcher.group("buildNumber")
            version = awtMatcher.group("version")
            product = awtMatcher.group("product")
        }
    }

    override fun onMatch(matcher: Matcher, threadDumpInfo: ThreadDumpInfo, builder: ThreadInfoDigest.Builder) {
        val threadName = matcher.group("threadName")

        builder.threadName(threadName)
        checkAWT(threadName, threadDumpInfo)
    }
}

object TreadStateMatchAction : RegexLineMatchAction() {
    override val pattern = "^java.lang.Thread.State: (?<threadState>.*)".toPattern()

    override fun onMatch(matcher: Matcher, threadDumpInfo: ThreadDumpInfo, builder: ThreadInfoDigest.Builder) {
        val state = Thread.State.valueOf(matcher.group("threadState"))

        builder.threadState(state)
    }
}

object InNativeMatchAction : RegexLineMatchAction() {
    override val pattern = "(in native)".toPattern()

    override fun onMatch(matcher: Matcher, threadDumpInfo: ThreadDumpInfo, builder: ThreadInfoDigest.Builder) {
        builder.inNative(true)
    }
}

object SuspendedMatchAction : RegexLineMatchAction() {
    override val pattern = "(suspended)".toPattern()

    override fun onMatch(matcher: Matcher, threadDumpInfo: ThreadDumpInfo, builder: ThreadInfoDigest.Builder) {
        builder.suspended(true)
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

    override fun onMatch(matcher: Matcher, threadDumpInfo: ThreadDumpInfo, builder: ThreadInfoDigest.Builder) {
        builder.lockName(matcher.group("lockName"))
        checkLockOwnerName(matcher.group("lockOwnerName"), builder)
    }
}

object StackTraceElementMatchAction : RegexLineMatchAction() {
    override val pattern = "^at (?<entryPoint>.*?)\\((?<fileInfo>.*?)\\)".toPattern()

    override fun onMatch(matcher: Matcher, threadDumpInfo: ThreadDumpInfo, builder: ThreadInfoDigest.Builder) {
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

        builder.stackTrace(StackTraceElement(className, methodName, fileName, lineNumber))
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

    return infos
}

fun parseThreadDumpInfo(s: String): ThreadDumpInfo? {
    val dumpInfo = ThreadDumpInfo().apply { objectId = ObjectId.get() }
    val threadListString = sliceThreadInfos(s)
    val threadList = ArrayList<ThreadInfoDigest>()

    threadListString.forEach { threadInfoString ->
        val builder = ThreadInfoDigest.Builder()

        threadInfoString.forEach { line ->
            LineMatchAction.list.find { it.tryMatch(line.trim(), dumpInfo, builder) } ?: throw IllegalStateException("$line not handled")
        }

        threadList.add(builder.build())
    }

    if (threadList.isEmpty()) return null

    return dumpInfo.apply { this.threadInfos = threadList }
}