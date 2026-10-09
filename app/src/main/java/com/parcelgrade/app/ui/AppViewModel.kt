package com.parcelgrade.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parcelgrade.app.data.AiResearch
import com.parcelgrade.app.data.Analyzer
import com.parcelgrade.app.data.Settings
import com.parcelgrade.app.data.Step
import com.parcelgrade.app.data.StepStatus
import com.parcelgrade.app.domain.Report
import com.parcelgrade.app.domain.UserInputs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class Screen { HOME, PROGRESS, REPORT, SETTINGS }

data class Recent(val address: String, val grade: String, val score: Int)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("parcelgrade", Context.MODE_PRIVATE)

    var screen by mutableStateOf(Screen.HOME)
        private set
    var settings by mutableStateOf(loadSettings())
        private set
    var recents by mutableStateOf(loadRecents())
        private set
    var report by mutableStateOf<Report?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var currentAddress by mutableStateOf("")
        private set
    val steps = mutableStateMapOf<Step, Pair<StepStatus, String?>>()

    private var job: Job? = null

    fun analyze(address: String, askingPrice: Double?, acres: Double?) {
        if (address.isBlank()) return
        job?.cancel()
        steps.clear()
        Step.values().forEach { steps[it] = StepStatus.WAITING to null }
        report = null
        error = null
        currentAddress = address.trim()
        screen = Screen.PROGRESS
        val analyzer = Analyzer(settings) { s, st, note ->
            viewModelScope.launch(Dispatchers.Main) { steps[s] = st to note }
        }
        job = viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.Default) {
                    analyzer.run(UserInputs(currentAddress, askingPrice, acres))
                }
                report = r
                saveRecent(Recent(currentAddress, r.grade, r.overallScore.toInt()))
                screen = Screen.REPORT
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.message ?: "Something went wrong. Check your connection and try again."
            }
        }
    }

    fun cancel() {
        job?.cancel()
        screen = Screen.HOME
    }

    fun go(s: Screen) { screen = s }

    fun back(): Boolean = when (screen) {
        Screen.HOME -> false
        Screen.PROGRESS -> { cancel(); true }
        else -> { screen = Screen.HOME; true }
    }

    fun saveSettings(s: Settings) {
        settings = s
        prefs.edit()
            .putString("anthropicKey", s.anthropicKey.trim())
            .putString("model", s.model.trim())
            .putString("regridToken", s.regridToken.trim())
            .apply()
        screen = Screen.HOME
    }

    fun clearRecents() {
        recents = emptyList()
        prefs.edit().remove("recents").apply()
    }

    private fun loadSettings() = Settings(
        anthropicKey = prefs.getString("anthropicKey", "") ?: "",
        model = prefs.getString("model", AiResearch.DEFAULT_MODEL) ?: AiResearch.DEFAULT_MODEL,
        regridToken = prefs.getString("regridToken", "") ?: "",
    )

    private fun loadRecents(): List<Recent> = runCatching {
        val a = JSONArray(prefs.getString("recents", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Recent(o.getString("a"), o.getString("g"), o.getInt("s"))
        }
    }.getOrDefault(emptyList())

    private fun saveRecent(r: Recent) {
        val list = (listOf(r) + recents.filter { !it.address.equals(r.address, true) }).take(12)
        recents = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("a", it.address).put("g", it.grade).put("s", it.score)) }
        prefs.edit().putString("recents", a.toString()).apply()
    }
}
