package org.compscicenter.typingFreezeAnalyzer

import com.mongodb.BasicDBObject
import com.mongodb.MongoClient
import org.bson.types.ObjectId
import org.mongodb.morphia.Datastore
import org.mongodb.morphia.Morphia
import org.mongodb.morphia.converters.TypeConverter
import org.mongodb.morphia.mapping.MappedField
import org.mongodb.morphia.query.Query
import java.lang.management.LockInfo

object DatabaseInfo {
    const val DB_NAME = "test"
    const val TABLE_NAME = "ThreadDumps"
}

interface ThreadDumpDao {
    fun getAllThreadDumps(): List<ThreadDumpInfo>
    fun getThreadDump(objectId: ObjectId): ThreadDumpInfo
}

class ThreadDumpDaoMongo : ThreadDumpDao {
    val morphia = Morphia()
    val dataStore: Datastore = morphia.createDatastore(MongoClient(), DatabaseInfo.DB_NAME)

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

    fun getQuery(): Query<ThreadDumpInfo> = dataStore.createQuery(ThreadDumpInfo::class.java)
    override fun getAllThreadDumps(): List<ThreadDumpInfo> = getQuery().asList()
    override fun getThreadDump(objectId: ObjectId): ThreadDumpInfo = getQuery().field("objectId").equal(objectId).get()
}