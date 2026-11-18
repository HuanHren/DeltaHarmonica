package com.huanhren.deltaharmonica.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.huanhren.deltaharmonica.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyBackupTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun savingMigratedLayoutKeepsOriginalAndDoesNotReplaceInvalidData() = runBlocking {
        val file = temp.newFile("legacy.preferences_pb").also { it.delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val key = stringPreferencesKey("layout_json")
            val old = InstrumentLayout("x", "口风琴布局", "三角洲行动", "口风琴", LayoutOrientation.LANDSCAPE,
                (1..8).map { TouchTarget("key-$it", "K$it", NormalizedPoint(it/10f, .5f)) }, 1, 2)
            val raw = LayoutCodec.encode(old).replace("\"schemaVersion\":2", "\"schemaVersion\":1")
            store.edit { it[key] = raw }
            val repo = LayoutRepository(store)
            val migrated = repo.load()!!
            repo.save(migrated)
            assertEquals(raw, repo.legacyBackup())
            assertEquals(migrated, repo.load())
            store.edit { it[key] = "broken" }
            assertTrue(runCatching { repo.save(migrated) }.isFailure)
            assertTrue(runCatching { repo.load() }.isFailure)
            assertEquals(raw, repo.legacyBackup())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
