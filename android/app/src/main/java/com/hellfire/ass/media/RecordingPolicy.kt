package com.hellfire.ass.media

import java.util.Locale

object RecordingPolicy {
    fun exclusions(text: String): List<String> = text.split(',', '\n').map(String::trim).filter(String::isNotEmpty).also { values ->
        require(values.size <= 100 && values.all { it.length <= 100 })
    }
    fun allowed(participants: String, place: String, excludedPeople: String, excludedPlaces: String): Boolean {
        require(participants.length <= 2000 && place.length <= 200)
        fun matches(context: String, excluded: String) = context.lowercase(Locale.ROOT).contains(excluded.lowercase(Locale.ROOT))
        return exclusions(excludedPeople).none { matches(participants, it) } && exclusions(excludedPlaces).none { matches(place, it) }
    }
}
