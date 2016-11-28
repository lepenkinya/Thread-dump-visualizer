package org.compscicenter.typingFreezeAnalyzer

import org.apache.commons.io.IOUtils
import java.util.zip.ZipFile

fun main(args: Array<String>) {
    val zipFile = ZipFile("/home/mnasimov/Downloads/test.zip")

    try {
        val entries = zipFile.entries()

        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()

            if (!entry.isDirectory) {
                zipFile.getInputStream(entry).buffered().use {
                    val string = IOUtils.toString(it, "UTF-8")
                    println(string)
                }
            }
        }
    } finally {
        zipFile.close()
    }
}