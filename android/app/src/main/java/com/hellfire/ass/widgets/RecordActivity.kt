package com.hellfire.ass.widgets

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.MainActivity
import com.hellfire.ass.audio.MicrophoneService
import com.hellfire.ass.audio.MicrophoneStatus
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HellFireTheme
import kotlinx.coroutines.launch
import com.hellfire.ass.Diagnostics
import com.hellfire.ass.security.AppLock

/** In-memory consent for widget recording. It ends with the process or after an hour; it is never stored. */
object RecordSession {
    var confirmedAt = 0L
    var participants = ""
    var place = ""
    fun valid(now: Long = System.currentTimeMillis()) = confirmedAt > 0 && now - confirmedAt < 60 * 60_000
}

/**
 * Opened by the Record widget. Stops a running recording, or starts one through the same consent and exclusion
 * check as Settings › Recordings (MicrophoneService re-checks the saved exclusions). Recording is always a
 * visible foreground service with its notification and never restarts by itself.
 */
class RecordActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HellFireApp
        if (MicrophoneStatus.busy.value) {
            startService(Intent(this, MicrophoneService::class.java).setAction(MicrophoneService.ACTION_STOP))
            Toast.makeText(this, "Recording stopped. Saved encrypted on this phone.", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        if (AppLock.enabled(this)) {
            startActivity(Intent(this, MainActivity::class.java).setData(Uri.parse("hellfire://settings/recordings")))
            finish(); return
        }
        val ready = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        if (ready && RecordSession.valid()) { start(app, RecordSession.participants, RecordSession.place); finish(); return }
        setContent {
            HellFireTheme {
                var notice by remember { mutableStateOf(false) }
                var people by remember { mutableStateOf(RecordSession.participants) }
                var place by remember { mutableStateOf(RecordSession.place) }
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x73111418)), contentAlignment = Alignment.BottomCenter) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Hf.colors.surface)
                        .navigationBarsPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Record a conversation", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
                        Note("Visible recording, encrypted on this phone, up to 30 minutes. A notification shows while it runs. People and places you excluded in Settings › Recordings are checked before it starts.")
                        if (!ready) {
                            Banner("Allow the microphone and notifications first.", Tone.Warning, null)
                            PrimaryButton("Open HellFire", {
                                startActivity(Intent(this@RecordActivity, MainActivity::class.java).setData(Uri.parse("hellfire://settings/permissions")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                finish()
                            }, Modifier.fillMaxWidth())
                        } else {
                            HfTextField(people, { people = it }, "People present (you type them)")
                            HfTextField(place, { place = it }, "Where you are")
                            CheckRow("Everyone present knows and agrees to recording.", notice, onChange = { notice = it })
                            PrimaryButton("Start recording", {
                                RecordSession.confirmedAt = System.currentTimeMillis(); RecordSession.participants = people; RecordSession.place = place
                                start(app, people, place); finish()
                            }, Modifier.fillMaxWidth(), enabled = notice, height = 52.dp)
                        }
                        QuietButton("Cancel", { finish() }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    private fun start(app: HellFireApp, people: String, place: String) {
        try {
            val requested = MicrophoneService.requestStart(this, Intent(this, MicrophoneService::class.java).setAction(MicrophoneService.ACTION_START)
                .putExtra("monitoring", true).putExtra("notice", true).putExtra("participants", people).putExtra("place", place).putExtra("queue", true))
            if (!requested) Toast.makeText(this, MicrophoneStatus.state.value, Toast.LENGTH_LONG).show()
        } catch (caught: Exception) { Diagnostics.warn(caught);
            Toast.makeText(this, "Android blocked recording. Open HellFire and try from Settings › Recordings.", Toast.LENGTH_LONG).show()
        }
        app.captureScope.launch { Widgets.refreshRecord(app) }
    }
}
