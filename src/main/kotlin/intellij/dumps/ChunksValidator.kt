package intellij.dumps


fun List<DumpChunk>.validate(): ValidationResult {
    val threads = trimTextChunks().split({ it is ThreadHeaderChunk }, includeSplitter = true)
    val textChunksInsideTrace = threads.map { trace -> trace.any { it is TextChunk } }.any { it }

    if (textChunksInsideTrace) {
        return ValidationResult(false, "Text Chunks Inside Stack Trace")
    }

    return ValidationResult(true, "")
}


fun List<DumpChunk>.trimTextChunks(): List<DumpChunk> {
    return dropWhile { it is TextChunk }.dropLastWhile { it is TextChunk }
}


fun List<DumpChunk>.hasTextChunks(): Boolean {
    return any { it is TextChunk }
}


data class ValidationResult(val isOK: Boolean, val message: String)


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