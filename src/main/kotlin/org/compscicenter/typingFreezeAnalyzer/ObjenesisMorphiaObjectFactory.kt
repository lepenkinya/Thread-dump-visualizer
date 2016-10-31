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

            if (constructor != null) {
                return constructor.newInstance()
            }

            try {
                return Objenesis.instance.getInstantiatorOf<T>(clazz).newInstance()
            } catch (e: Exception) {
                throw MappingException("Failed to instantiate " + clazz.name, e)
            }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    private fun <T> getNoArgsConstructor(ctorType: Class<T>): Constructor<T>? {
        try {
            val ctor = ctorType.getDeclaredConstructor()
            ctor.isAccessible = true
            return ctor
        } catch (e: NoSuchMethodException) {
            return null
        }
    }
}