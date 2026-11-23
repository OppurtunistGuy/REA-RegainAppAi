package app.steady.rules

enum class Energy { LOW, OKAY, SHARP }

/** Suggested focus-block length for how the user feels right now. */
object EnergyPlanner {
    fun blockMinutes(energy: Energy): Int = when (energy) {
        Energy.LOW -> 15
        Energy.OKAY -> 25
        Energy.SHARP -> 40
    }
}
