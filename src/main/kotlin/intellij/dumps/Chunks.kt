package intellij.dumps


interface ChunkFactory {
    fun chunk(line: String): DumpChunk?

    companion object {
        private val factories = listOf(
                ThreadHeaderFactory(),
                StackTraceChunkFactory()
        )

        private val textFactory = TextChunkFactory()

        fun chunk(line: String): DumpChunk {
            factories.forEach {
                val chunk = it.chunk(line)
                if (chunk != null) {
                    return chunk
                }
            }
            return textFactory.chunk(line)
        }
    }
}


abstract class DumpChunk(initial: String) {
    protected val lines = mutableListOf<String>()
    init {
        lines.add(initial)
    }

    abstract fun acceptsNextLine(line: String): Boolean

    fun feedLine(line: String) {
        lines.add(line)
    }
}


class ThreadHeaderFactory : ChunkFactory {
    override fun chunk(line: String): DumpChunk? {
        val trimmed = line.trim()
        return if (trimmed.startsWith("\"")) ThreadHeaderChunk(line) else null
    }
}


class ThreadHeaderChunk(line: String) : DumpChunk(line) {
    override fun acceptsNextLine(line: String): Boolean {
        val trimmed = line.trim()
        if (lines.size == 1) {
            return trimmed.startsWith("java.lang.Thread.State")
        }
        else if (lines.size == 2) {
            return trimmed.startsWith("on") || trimmed.startsWith("(in native)")
        }
        return false
    }
}


class TextChunkFactory : ChunkFactory {
    override fun chunk(line: String): DumpChunk {
        return TextChunk(line)
    }
}


class TextChunk(line: String): DumpChunk(line) {
    override fun acceptsNextLine(line: String) = false
}


class StackTraceChunkFactory: ChunkFactory {
    override fun chunk(line: String): DumpChunk? {
        val trimmed = line.trim()
        return if (trimmed.startsWith("at")) StackTraceChunk(line) else null
    }
}


class StackTraceChunk(line: String): DumpChunk(line) {
    override fun acceptsNextLine(line: String): Boolean {
        if (lines.size != 1) {
            return false
        }
        return line.trim().startsWith("-")
    }
}




