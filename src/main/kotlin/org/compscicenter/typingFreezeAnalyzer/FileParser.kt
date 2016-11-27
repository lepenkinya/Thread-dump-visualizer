//package org.compscicenter.typingFreezeAnalyzer
//
//import org.apache.commons.io.IOUtils
//import org.apache.commons.lang3.reflect.FieldUtils
//import org.bson.types.ObjectId
//import java.lang.management.ThreadInfo
//import java.util.zip.ZipFile
//
//fun main(args: Array<String>) {
//    val newInstance = Objenesis.instance.getInstantiatorOf<ThreadInfo>(ThreadInfo::class.java).newInstance()
//
//    val field = FieldUtils.getField(ThreadInfo::class.java, "threadName", true)
//    FieldUtils.writeField(field, newInstance, "test")
//
//    val zipFile = ZipFile("/home/mnasimov/Downloads/test.zip")
//
//    try {
//        val entries = zipFile.entries()
//
//        while (entries.hasMoreElements()) {
//            val entry = entries.nextElement()
//
//            if (!entry.isDirectory) {
//                val threadDumpInfo = ThreadDumpInfo()
//
//                threadDumpInfo.objectId = ObjectId()
//
//                zipFile.getInputStream(entry).buffered().use {
//                    val string = IOUtils.toString(it, "UTF-8")
//                    println(string)
//                }
//            }
//        }
//
//    } finally {
//        zipFile.close()
//    }
//}