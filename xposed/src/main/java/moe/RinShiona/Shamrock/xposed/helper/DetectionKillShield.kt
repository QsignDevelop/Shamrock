package moe.RinShiona.Shamrock.xposed.helper

/**
 * Arms a short window where QQ must not self-terminate after a hook scan.
 * QSec.detectMethod / dtcProcessCall / doSomething often trigger kill right after.
 */
internal object DetectionKillShield {
    @Volatile
    private var armedUntilMs: Long = 0L

    /** Default 45s covers ArtTiHookTask + follow-up sensitive method probes. */
    fun arm(durationMs: Long = 45_000L) {
        val until = System.currentTimeMillis() + durationMs
        if (until > armedUntilMs) {
            armedUntilMs = until
        }
    }

    fun isArmed(): Boolean = System.currentTimeMillis() < armedUntilMs
}
