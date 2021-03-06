package org.compscicenter.threadDumpVisualizer.mongo

import com.mongodb.BasicDBObject
import com.mongodb.MongoClient
import intellij.dumps.Dump
import org.bson.types.ObjectId
import org.compscicenter.threadDumpVisualizer.MongoConfig
import org.mongodb.morphia.Datastore
import org.mongodb.morphia.Morphia
import org.mongodb.morphia.converters.TypeConverter
import org.mongodb.morphia.mapping.MappedField
import java.lang.management.LockInfo

interface ThreadDumpDao {
    fun getAllThreadDumps(): List<Dump>
    fun getThreadDump(objectId: ObjectId): Dump
}

class ThreadDumpDaoMongo(mongoConfig: MongoConfig) : ThreadDumpDao {
    val morphia = Morphia()
    val dataStore: Datastore = with(mongoConfig) { morphia.createDatastore(MongoClient(host, port), dbName) }

    init {
        dataStore.ensureIndexes()
        morphia.mapper.options.objectFactory = ObjenesisMorphiaObjectFactory()

        // need some hack to deserialize LockInfo
        morphia.mapper.converters.addConverter(object : TypeConverter(LockInfo::class.java) {
            override fun decode(targetClass: Class<*>, fromDBObject: Any?, optionalExtraInfo: MappedField?): LockInfo {
                val basicDBObject = fromDBObject as BasicDBObject
                return LockInfo(basicDBObject.get("className") as String, basicDBObject.get("identityHashCode") as Int)
            }
        })
    }

    override fun getAllThreadDumps(): List<Dump> {
        return emptyList()
        //return dataStore.createQuery(ThreadDumpInfo.Builder::class.java).asList().map { it.build() }
    }

    override fun getThreadDump(objectId: ObjectId): Dump {
        TODO()
        //return dataStore.createQuery(ThreadDumpInfo.Builder::class.java).field("objectId").equal(objectId).get().build()
    }
}