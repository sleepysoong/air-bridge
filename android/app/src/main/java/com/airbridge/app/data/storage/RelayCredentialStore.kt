package com.airbridge.app.data.storage

import com.airbridge.app.domain.StoredRelayCredentials

class RelayCredentialStore(
    private val securePreferencesStore: SecurePreferencesStore,
) {
    fun read(): StoredRelayCredentials? =
        securePreferencesStore.read(KEY, StoredRelayCredentials.serializer())

    fun write(credentials: StoredRelayCredentials) {
        securePreferencesStore.write(KEY, StoredRelayCredentials.serializer(), credentials)
    }

    fun clear() {
        securePreferencesStore.remove(KEY)
    }

    companion object {
        private const val KEY = "relay_credentials"
    }
}
