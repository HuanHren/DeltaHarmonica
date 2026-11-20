package com.huanhren.deltaharmonica.input

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.rootAuthorizationStore by preferencesDataStore(name = "root_authorization")

internal class AndroidRootAuthorization(private val context: Context) : RootAuthorizationStore {
    suspend fun detect(): RootEnvironment = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val managers = RootManagerKind.entries.mapNotNull { kind ->
            try {
                @Suppress("DEPRECATION")
                val record = packageManager.getPackageInfo(kind.packageName, 0)
                RootManagerInstallation(kind, record.lastUpdateTime)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
        // A visible candidate cannot attest that its daemon is running or that the app is granted.
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.startsWith('/') }
        val dirs = path + listOf("/system/bin", "/system/xbin", "/sbin")
        val suVisible = dirs.distinct().any { dir -> File(dir, "su").canExecute() }
        RootEnvironment(suVisible, managers.singleOrNull())
    }

    override suspend fun read(): RootAuthorizationRecord? {
        val prefs = context.rootAuthorizationStore.data.first()
        val authorization = RootAuthorizationState.entries.firstOrNull { it.name == prefs[Keys.authorization] } ?: return null
        val checkedAt = prefs[Keys.checkedAt] ?: return null
        val kind = RootManagerKind.entries.firstOrNull { it.name == prefs[Keys.manager] }
        val manager = kind?.let { RootManagerInstallation(it, prefs[Keys.managerUpdated] ?: 0) }
        return RootAuthorizationRecord(
            environment = RootEnvironment(prefs[Keys.suVisible], manager),
            authorization = authorization,
            lastCheckedAt = checkedAt,
            lastAuthorizedAt = prefs[Keys.authorizedAt],
            input = RootInputReadiness.entries.firstOrNull { it.name == prefs[Keys.input] } ?: RootInputReadiness.UNKNOWN,
            failure = prefs[Keys.failure],
        )
    }

    override suspend fun write(record: RootAuthorizationRecord) {
        context.rootAuthorizationStore.edit { prefs ->
            prefs[Keys.authorization] = record.authorization.name
            prefs[Keys.checkedAt] = record.lastCheckedAt
            prefs[Keys.input] = record.input.name
            record.environment.suVisible?.let { prefs[Keys.suVisible] = it } ?: prefs.remove(Keys.suVisible)
            record.lastAuthorizedAt?.let { prefs[Keys.authorizedAt] = it } ?: prefs.remove(Keys.authorizedAt)
            record.environment.manager?.let {
                prefs[Keys.manager] = it.kind.name
                prefs[Keys.managerUpdated] = it.updatedAt
            } ?: run {
                prefs.remove(Keys.manager)
                prefs.remove(Keys.managerUpdated)
            }
            record.failure?.let { prefs[Keys.failure] = it.take(240) } ?: prefs.remove(Keys.failure)
        }
    }

    private object Keys {
        val authorization = stringPreferencesKey("authorization")
        val checkedAt = longPreferencesKey("checked_at")
        val authorizedAt = longPreferencesKey("authorized_at")
        val input = stringPreferencesKey("input")
        val suVisible = booleanPreferencesKey("su_visible")
        val manager = stringPreferencesKey("manager")
        val managerUpdated = longPreferencesKey("manager_updated")
        val failure = stringPreferencesKey("failure")
    }
}
