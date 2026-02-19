package com.tudorc.mediabus.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tudorc.mediabus.model.HostSettings
import com.tudorc.mediabus.model.PairedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

private val Context.hostStore: DataStore<Preferences> by preferencesDataStore(name = "mediabus_store")

class HostStoreRepository(private val appContext: Context) {
    private val store: DataStore<Preferences> = appContext.hostStore

    val settingsFlow: Flow<HostSettings> = store.data.map { preferences ->
        HostSettings(
            sharedFolderUri = preferences[sharedFolderUriKey],
            showHiddenFiles = preferences[showHiddenFilesKey] ?: false,
            allowUpload = preferences[allowUploadKey] ?: true,
            allowDownload = preferences[allowDownloadKey] ?: true,
            allowDelete = preferences[allowDeleteKey] ?: true,
        )
    }

    val pairedDevicesFlow: Flow<List<PairedDevice>> = store.data.map { preferences ->
        decodePairedDevices(preferences[pairedDevicesJsonKey])
    }

    suspend fun getSettings(): HostSettings = settingsFlow.first()

    suspend fun getPairedDevices(): List<PairedDevice> = pairedDevicesFlow.first()

    suspend fun setSharedFolderUri(value: String?) {
        store.edit { preferences ->
            if (value == null) {
                preferences.remove(sharedFolderUriKey)
            } else {
                preferences[sharedFolderUriKey] = value
            }
        }
    }

    suspend fun setShowHiddenFiles(enabled: Boolean) {
        store.edit { preferences ->
            preferences[showHiddenFilesKey] = enabled
        }
    }

    suspend fun setAllowUpload(enabled: Boolean) {
        store.edit { preferences ->
            preferences[allowUploadKey] = enabled
        }
    }

    suspend fun setAllowDownload(enabled: Boolean) {
        store.edit { preferences ->
            preferences[allowDownloadKey] = enabled
        }
    }

    suspend fun setAllowDelete(enabled: Boolean) {
        store.edit { preferences ->
            preferences[allowDeleteKey] = enabled
        }
    }

    suspend fun savePairedDevices(devices: List<PairedDevice>) {
        store.edit { preferences ->
            preferences[pairedDevicesJsonKey] = encodePairedDevices(devices)
        }
    }

    suspend fun removePairedDevice(deviceId: String) {
        store.edit { preferences ->
            val devices = decodePairedDevices(preferences[pairedDevicesJsonKey])
            val updated = devices.filterNot { it.deviceId == deviceId }
            preferences[pairedDevicesJsonKey] = encodePairedDevices(updated)
        }
    }

    suspend fun getOrCreateSigningSecret(): ByteArray {
        val existing = store.data.first()[signingSecretKey]
        if (!existing.isNullOrBlank()) {
            return Base64.getUrlDecoder().decode(existing)
        }

        val generated = ByteArray(32).also { random.nextBytes(it) }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(generated)
        store.edit { preferences ->
            preferences[signingSecretKey] = encoded
        }
        return generated
    }

    private fun decodePairedDevices(raw: String?): List<PairedDevice> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        PairedDevice(
                            deviceId = item.optString("deviceId"),
                            displayName = item.optString("displayName"),
                            userAgent = item.optString("userAgent"),
                            lastKnownIp = item.optString("lastKnownIp"),
                            createdAtEpochMs = item.optLong("createdAtEpochMs"),
                            lastConnectedEpochMs = item.optLong("lastConnectedEpochMs"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodePairedDevices(devices: List<PairedDevice>): String {
        val array = JSONArray()
        devices.forEach { device ->
            array.put(
                JSONObject().apply {
                    put("deviceId", device.deviceId)
                    put("displayName", device.displayName)
                    put("userAgent", device.userAgent)
                    put("lastKnownIp", device.lastKnownIp)
                    put("createdAtEpochMs", device.createdAtEpochMs)
                    put("lastConnectedEpochMs", device.lastConnectedEpochMs)
                },
            )
        }
        return array.toString()
    }

    private companion object {
        private val sharedFolderUriKey = stringPreferencesKey("shared_folder_uri")
        private val showHiddenFilesKey = booleanPreferencesKey("show_hidden_files")
        private val allowUploadKey = booleanPreferencesKey("allow_upload")
        private val allowDownloadKey = booleanPreferencesKey("allow_download")
        private val allowDeleteKey = booleanPreferencesKey("allow_delete")
        private val pairedDevicesJsonKey = stringPreferencesKey("paired_devices_json")
        private val signingSecretKey = stringPreferencesKey("signing_secret")
        private val random = SecureRandom()
    }
}
