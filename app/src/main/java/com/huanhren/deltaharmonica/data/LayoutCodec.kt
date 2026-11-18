package com.huanhren.deltaharmonica.data

import com.huanhren.deltaharmonica.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable private data class LayoutEnvelope(val schemaVersion: Int, val layout: InstrumentLayout)

object LayoutCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun encode(layout: InstrumentLayout): String = json.encodeToString(LayoutEnvelope(2, layout))
    fun decode(value: String): InstrumentLayout {
        val envelope = json.decodeFromString<LayoutEnvelope>(value)
        require(envelope.schemaVersion in 1..2) { "不支持的布局版本：${envelope.schemaVersion}；原始数据未修改" }
        return if (envelope.schemaVersion == 1) migrateLegacy(envelope.layout) else envelope.layout
    }
    fun isLegacy(value: String): Boolean {
        val envelope = json.decodeFromString<LayoutEnvelope>(value)
        require(envelope.schemaVersion in 1..2) { "不支持的布局版本：${envelope.schemaVersion}；原始数据未修改" }
        return envelope.schemaVersion == 1
    }
    private fun migrateLegacy(layout: InstrumentLayout): InstrumentLayout {
        val legacyIds = (1..8).map { "key-$it" }.toSet()
        if (layout.name != "口风琴布局" || layout.game != "三角洲行动" || layout.instrument != "口风琴" ||
            layout.targets.size != 8 || layout.targets.map { it.id }.toSet() != legacyIds) return layout
        val notes = layout.targets.map { target ->
            val index = target.id.removePrefix("key-").toInt() - 1
            target.copy(type = TargetType.NOTE, attributes = target.attributes + ("keyIndex" to index.toString()))
        }
        return layout.copy(targets = notes + DefaultLayouts.controls(), profile = DefaultProfiles.delta())
    }
}

