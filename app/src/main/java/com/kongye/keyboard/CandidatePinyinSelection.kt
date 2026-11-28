package com.kongye.keyboard

/** Explicit phonetic choices constrain candidates without committing text or changing raw keys. */
@androidx.annotation.Keep
class CandidatePinyinSelection {
    var selected: List<String> = emptyList()
        private set
    private var source: List<String> = emptyList()

    fun syllables(reading: String?): List<String> = reading?.let {
        HanReadings.plain(it).split(' ', '\'').filter(String::isNotEmpty)
    }.orEmpty()

    fun matches(reading: String?): Boolean = selected.isEmpty() || syllables(reading).take(selected.size) == selected

    fun options(readings: List<String?>): List<String> = readings.asSequence().map(::syllables)
        .filter { it.take(selected.size) == selected }.mapNotNull { it.getOrNull(selected.size) }.distinct().toList()

    fun choose(sound: String, readings: List<String?>): Boolean {
        if (sound !in options(readings)) return false
        selected = selected + sound
        return true
    }

    fun undo(): Boolean {
        if (selected.isEmpty()) return false
        selected = selected.dropLast(1)
        return true
    }

    /** Appending keeps the selected prefix; replacement or editing invalidates it. */
    fun updateInput(keys: List<String>): Boolean {
        val reset = selected.isNotEmpty() && keys.take(source.size) != source
        if (reset) selected = emptyList()
        source = keys.toList()
        return reset
    }

    fun restore(keys: List<String>, sounds: List<String>) {
        source = keys.toList(); selected = sounds.toList()
    }

    fun reset() { selected = emptyList(); source = emptyList() }

    fun underlineLength(display: String): Int = if (selected.isEmpty()) 0
        else display.split('\'').take(selected.size).joinToString("'").length
}
