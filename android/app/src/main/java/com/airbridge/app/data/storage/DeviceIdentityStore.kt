package com.airbridge.app.data.storage

import com.airbridge.app.domain.StoredDeviceIdentity

class DeviceIdentityStore(
    private val securePreferencesStore: SecurePreferencesStore,
) {
    fun read(): StoredDeviceIdentity? =
        securePreferencesStore.read(KEY, StoredDeviceIdentity.serializer())

    fun write(identity: StoredDeviceIdentity) {
        securePreferencesStore.write(KEY, StoredDeviceIdentity.serializer(), identity)
    }

    fun clear() {
        securePreferencesStore.remove(KEY)
    }

    companion object {
        private const val KEY = "device_identity"
    }
}

