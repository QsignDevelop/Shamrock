package moe.RinShiona.Shamrock.tools

fun Result<*>.errMsg(): String {
    return this.exceptionOrNull()?.message ?: exceptionOrNull().toString()
}