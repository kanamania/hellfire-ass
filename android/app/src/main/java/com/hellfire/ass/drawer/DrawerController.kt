package com.hellfire.ass.drawer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

/** In-app drawer visibility. Closing or minimizing does not delete a saved item. */
object DrawerController {
    var expanded by mutableStateOf(false)
    var inboxId by mutableStateOf<String?>(null)
    var sessionId by mutableStateOf(UUID.randomUUID().toString())
    var miniPlayerId by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)

    fun show(id: String) {
        inboxId = id
        expanded = true
        status = null
    }

    fun collapse() { expanded = false }
}
