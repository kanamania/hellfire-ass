package com.hellfire.ass.ui

import androidx.compose.runtime.*
import com.hellfire.ass.HellFireApp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Private form drafts never enter Android's saved-instance bundle or portable backups. */
object PrivateDrafts {
    /** String resource of the last draft storage failure, shown as a banner. */
    val error = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    private val lock = Mutex()
    private val versions = mutableMapOf<String, Long>()
    suspend fun read(app: HellFireApp, key: String): String? = read(app.secrets, key)
    suspend fun read(store: com.hellfire.ass.security.SecretStore, key: String): String? = lock.withLock { store.read("ui-drafts")?.optString(key)?.takeIf { it.isNotEmpty() } }
    fun write(app: HellFireApp, key: String, value: String): Job = write(app.secrets, app.captureScope, key, value)
    fun write(store: com.hellfire.ass.security.SecretStore, scope: CoroutineScope, key: String, value: String): Job {
        require(value.length <= 60000)
        val version = synchronized(versions) { (versions[key] ?: 0L).plus(1).also { versions[key] = it } }
        return scope.launch {
            try { lock.withLock {
                if (synchronized(versions) { versions[key] } != version) return@withLock
                val all = store.read("ui-drafts") ?: JSONObject()
                if (value.isEmpty()) all.remove(key) else all.put(key, value)
                if (all.length() == 0) store.remove("ui-drafts") else store.save("ui-drafts", all)
                error.value = null
            } } catch (e: Exception) { com.hellfire.ass.Diagnostics.warn(e); error.value = com.hellfire.ass.R.string.ui_draft_could_not_be_saved_keep_this }
        }
    }
}

@Composable
fun rememberPrivateDraft(key: String, initial: String = ""): MutableState<String> {
    val app = LocalEnv.current.app
    val state = remember(key) { mutableStateOf(initial) }
    var edited by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        val saved = try { withContext(Dispatchers.IO) { PrivateDrafts.read(app, key) } } catch (e: Exception) { PrivateDrafts.error.value = com.hellfire.ass.R.string.ui_draft_could_not_be_opened_check_storage; null }
        if (!edited && saved != null) state.value = saved
    }
    return remember(key) { object : MutableState<String> {
        override var value: String
            get() = state.value
            set(value) { val bounded = value.take(60000); edited = true; state.value = bounded; PrivateDrafts.write(app, key, bounded) }
        override fun component1() = value
        override fun component2(): (String) -> Unit = { value = it }
    } }
}
