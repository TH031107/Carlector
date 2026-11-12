package com.drivedex.prototype

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class GameStore(context: Context, automotive: Boolean) {
    private val prefs = context.getSharedPreferences(
        if (automotive) "carlector_vehicle" else "drivedex_demo",
        Context.MODE_PRIVATE
    )

    fun load(): GameState {
        val raw = prefs.getString("state", null) ?: return GameState()
        // Fail visibly on corrupted storage instead of silently resetting awarded missions.
        val json = JSONObject(raw)
        val paid = json.getJSONArray("paid")
        val owned = json.getJSONObject("owned")
        return GameState(
            tickets = json.getInt("tickets"),
            paid = (0 until paid.length()).map { paid.getString(it) }.toSet(),
            owned = owned.keys().asSequence().associateWith { owned.getInt(it) },
            warningActive = json.getBoolean("warningActive"),
            warningEpisode = json.getInt("warningEpisode"),
            lastCard = json.optString("lastCard").takeIf { it.isNotEmpty() },
            progressDay = json.optString("progressDay"),
            dailyProgress = Progress(json.optLong("movingMs"), json.optDouble("distanceM", 0.0)),
            activeCareWarnings = json.optJSONArray("activeCareWarnings")?.let { items ->
                CareItem.entries.filter { item -> (0 until items.length()).any { items.optString(it) == item.name } }.toSet()
            }.orEmpty()
        )
    }

    fun save(state: GameState): Boolean {
        val json = JSONObject()
            .put("tickets", state.tickets)
            .put("paid", JSONArray(state.paid.toList()))
            .put("owned", JSONObject(state.owned))
            .put("warningActive", state.warningActive)
            .put("warningEpisode", state.warningEpisode)
            .put("lastCard", state.lastCard ?: "")
            .put("progressDay", state.progressDay)
            .put("movingMs", state.dailyProgress.movingMs)
            .put("distanceM", state.dailyProgress.distanceM)
            .put("activeCareWarnings", JSONArray(state.activeCareWarnings.map { it.name }))
        return prefs.edit().putString("state", json.toString()).commit()
    }
}
