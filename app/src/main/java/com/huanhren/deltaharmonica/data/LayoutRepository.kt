package com.huanhren.deltaharmonica.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.huanhren.deltaharmonica.model.InstrumentLayout
import kotlinx.coroutines.flow.first

val Context.layoutDataStore by preferencesDataStore(name = "instrument_layout")

class LayoutRepository(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("layout_json")
    private val backupKey = stringPreferencesKey("layout_json_v1_backup")
    suspend fun load(): InstrumentLayout? = store.data.first()[key]?.let(LayoutCodec::decode)
    suspend fun save(layout: InstrumentLayout) {
        val encoded = LayoutCodec.encode(layout)
        store.edit { preferences ->
            preferences[key]?.let { old ->
                LayoutCodec.decode(old) // Refuse to replace damaged or unsupported data.
                if (LayoutCodec.isLegacy(old) && preferences[backupKey] == null) preferences[backupKey] = old
            }
            preferences[key] = encoded
        }
    }
    suspend fun legacyBackup(): String? = store.data.first()[backupKey]
}
