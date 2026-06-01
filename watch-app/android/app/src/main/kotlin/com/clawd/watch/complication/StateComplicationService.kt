package com.clawd.watch.complication

import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.clawd.watch.domain.ClawdState
import com.clawd.watch.domain.StateChipConfig

class StateComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("working").build(),
            contentDescription = PlainComplicationText.Builder("Agent state").build()
        ).build()
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val prefs = getSharedPreferences("clawd_state", Context.MODE_PRIVATE)
        val stateStr = prefs.getString("last_s", "idle") ?: "idle"
        val state = ClawdState.fromStringOrIdle(stateStr)
        val label = StateChipConfig.chipLabel(state)

        val data = ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(label).build(),
            contentDescription = PlainComplicationText.Builder("Agent: $label").build()
        ).build()

        listener.onComplicationData(data)
    }
}
