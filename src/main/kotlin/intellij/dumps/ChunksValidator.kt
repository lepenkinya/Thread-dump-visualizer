package intellij.dumps


fun List<DumpChunk>.validate(): ValidationResult {
    val threads: List<List<DumpChunk>> = trimTextChunks().split({ it is ThreadHeaderChunk }, includeSplitter = true)
    val textChunksInsideTrace = threads.map { trace -> trace.any { it is TextChunk } }.any { it }

    if (textChunksInsideTrace) {
        return ValidationResult(false, "Text Chunks Inside Stack Trace", emptyList(), emptyList(), emptyList())
    }

    val leadingChunks = takeWhile { it is TextChunk }
    val trailingChunks = takeLastWhile { it is TextChunk }

    return ValidationResult(true, "", threads, leadingChunks, trailingChunks)
}


fun List<DumpChunk>.trimTextChunks(): List<DumpChunk> {
    return dropWhile { it is TextChunk }.dropLastWhile { it is TextChunk }
}


data class ValidationResult(val isOK: Boolean,
                            val message: String,
                            val threads: List<List<DumpChunk>>,
                            val leadingChunks: List<DumpChunk>,
                            val trailingChunks: List<DumpChunk>)


fun <T> List<T>.split(predicate: (T) -> Boolean, includeSplitter: Boolean): List<List<T>> {
    if (indices.isEmpty()) return listOf()
    val result = mutableListOf<List<T>>()

    val current = mutableListOf<T>()
    for (e in this) {
        if (predicate(e)) {
            result.add(current.toList())
            current.clear()
            if (includeSplitter) {
                current.add(e)
            }
        }
        else {
            current.add(e)
        }
    }

    if (current.isNotEmpty()) {
        result.add(current)
    }

    return result
}