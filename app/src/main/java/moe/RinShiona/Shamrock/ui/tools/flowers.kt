package moe.RinShiona.Shamrock.ui.tools

operator fun <T> List<T>.get(range: IntRange): List<T> {
    return this.subList(range.first, range.last)
}

val <T> List<T>.last: T
    get() = this.last()
