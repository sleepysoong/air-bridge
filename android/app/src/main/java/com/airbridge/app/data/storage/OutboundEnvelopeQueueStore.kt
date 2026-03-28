package com.airbridge.app.data.storage

import com.airbridge.app.domain.PersistedOutboundEnvelope
import kotlinx.serialization.builtins.ListSerializer

class OutboundEnvelopeQueueStore(
    private val securePreferencesStore: SecurePreferencesStore,
) {
    private val serializer = ListSerializer(PersistedOutboundEnvelope.serializer())

    fun readAll(): List<PersistedOutboundEnvelope> =
        securePreferencesStore.read(KEY, serializer).orEmpty()

    fun replaceAll(items: List<PersistedOutboundEnvelope>) {
        if (items.isEmpty()) {
            securePreferencesStore.remove(KEY)
            return
        }

        securePreferencesStore.write(KEY, serializer, items.takeLast(MaxQueueSize))
    }

    fun enqueue(item: PersistedOutboundEnvelope) {
        val updated = readAll() + item
        replaceAll(updated)
    }

    fun remove(queueId: String) {
        replaceAll(readAll().filterNot { it.queueId == queueId })
    }

    fun clear() {
        securePreferencesStore.remove(KEY)
    }

    companion object {
        private const val KEY = "outbound_envelope_queue"
        private const val MaxQueueSize = 128
    }
}
