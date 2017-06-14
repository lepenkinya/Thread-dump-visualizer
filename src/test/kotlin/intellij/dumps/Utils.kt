package intellij.dumps

import java.io.InputStream


object Utils {

    private val classloader = javaClass.classLoader

    fun content(name: String): InputStream = classloader.getResourceAsStream(name)

}