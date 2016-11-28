package org.compscicenter.typingFreezeAnalyzer

import com.mongodb.BasicDBObject
import com.mongodb.MongoClient
import org.bson.types.ObjectId
import org.mongodb.morphia.Datastore
import org.mongodb.morphia.Morphia
import org.mongodb.morphia.converters.TypeConverter
import org.mongodb.morphia.mapping.MappedField
import java.lang.management.LockInfo

interface ThreadDumpDao {
    fun getAllThreadDumps(): List<ThreadDumpInfo>
    fun getThreadDump(objectId: ObjectId): ThreadDumpInfo
}

class ThreadDumpDaoMongo(map: Map<String, Any>) : ThreadDumpDao {
    constructor(host: String = "127.0.0.1", port: Int = 27017, dbName: String = "test")
            : this(mapOf("host" to host, "port" to port, "dbName" to dbName))
    val host: String by map
    val port: Int by map
    val dbName: String by map
    val morphia = Morphia()
    val dataStore: Datastore = morphia.createDatastore(MongoClient(host, port), dbName)

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

    override fun getAllThreadDumps(): List<ThreadDumpInfo> {
        return dataStore.createQuery(ThreadDumpInfo::class.java).asList()
    }

    override fun getThreadDump(objectId: ObjectId): ThreadDumpInfo {
        return dataStore.createQuery(ThreadDumpInfo::class.java).field("objectId").equal(objectId).get()
    }
}