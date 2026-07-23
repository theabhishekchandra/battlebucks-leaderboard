package com.abhishek.battlebucks.engine

/**
 * A competitor in a match.
 *
 * [id] is the identity. [displayName] is presentation data that rides along with the event so
 * consumers don't need a second lookup table. In a real backend the name would come from a profile
 * service and the event would carry only the id.
 */
data class Player(val id: String, val displayName: String)

/**
 * A fixed sample roster.
 *
 * It lives here rather than in the app because "maintain a list of players" is the engine's job.
 * The engine stands in for the backend, and the backend owns the roster.
 */
fun defaultRoster(size: Int = DEFAULT_ROSTER_SIZE): List<Player> {
    require(size in 1..PLAYER_NAMES.size) {
        "Roster size must be between 1 and ${PLAYER_NAMES.size}, was $size"
    }
    return PLAYER_NAMES.take(size).mapIndexed { index, name ->
        Player(id = "p${index + 1}", displayName = name)
    }
}

private const val DEFAULT_ROSTER_SIZE = 15

private val PLAYER_NAMES = listOf(
    "Deepender",
    "Predekin_Singh",
    "Himanshu",
    "Manya Aggarwal",
    "Vishal",
    "Shreyas",
    "Abhishek Chandra",
    "Anwesha",
    "Premjit",
    "Harshit",
    "Ayush Rawat",
    "Arshi",
    "Iqbal",
    "Shubham",
    "Aditya Patil",
)
