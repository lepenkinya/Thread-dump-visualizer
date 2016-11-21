package org.compscicenter.typingFreezeAnalyzer

import org.mongodb.morphia.mapping.DefaultCreator
import org.mongodb.morphia.mapping.MappingException
import org.objenesis.ObjenesisStd

import java.lang.reflect.Constructor

class ObjenesisMorphiaObjectFactory : DefaultCreator() {
    object Objenesis {
        val instance = ObjenesisStd()
    }

    override fun <T : Any> createInstance(clazz: Class<T>): T {
        try {
            val constructor = getNoArgsConstructor(clazz)

            try {
                return constructor?.newInstance() ?: Objenesis.instance.getInstantiatorOf<T>(clazz).newInstance()
            } catch (e: Exception) {
                throw MappingException("Failed to instantiate ${clazz.name}", e)
            }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    private fun <T> getNoArgsConstructor(ctorType: Class<T>): Constructor<T>? {
        try {
            return ctorType.getDeclaredConstructor().apply { isAccessible = true }
        } catch (e: NoSuchMethodException) {
            return null
        }
    }
}