package com.hellfire.ass

import android.app.Application
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.EventRepository
import com.hellfire.ass.security.PayloadCipher

class HellFireApp : Application() {
    val database by lazy { EventDatabase.open(this) }
    val cipher by lazy { PayloadCipher.deviceCipher() }
    val events by lazy { EventRepository(database.events()) { cipher } }
}
