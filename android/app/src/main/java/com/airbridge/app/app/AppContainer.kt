package com.airbridge.app.app

import android.content.Context
import com.airbridge.app.data.crypto.EnvelopeCipher
import com.airbridge.app.data.crypto.SessionKeyStore
import com.airbridge.app.data.relay.RelayHttpClient
import com.airbridge.app.data.relay.RelayWebSocketClient
import com.airbridge.app.data.storage.DeviceIdentityStore
import com.airbridge.app.data.storage.RelayCredentialStore
import com.airbridge.app.data.storage.SecurePreferencesStore
import com.airbridge.app.feature.clipboard.AndroidClipboardApplyGateway
import com.airbridge.app.feature.clipboard.AndroidClipboardReadGateway
import com.airbridge.app.feature.clipboard.ClipboardSyncCoordinator
import com.airbridge.app.feature.common.BridgeFeatureRegistry
import com.airbridge.app.feature.notification.AirBridgeNotificationListenerService
import com.airbridge.app.feature.notification.NotificationForwarder
import com.airbridge.app.feature.notification.NotificationNoiseFilter
import com.airbridge.app.feature.notification.NotificationPayloadNormalizer
import com.airbridge.app.feature.pairing.PairingRepository
import com.airbridge.app.feature.service.BridgeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class AppContainer(
    val appContext: Context,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.NONE
            },
        )
        .build()

    private val securePreferencesStore = SecurePreferencesStore(appContext, json)
    private val sessionKeyStore = SessionKeyStore()
    private val envelopeCipher = EnvelopeCipher(json, sessionKeyStore)

    val deviceIdentityStore = DeviceIdentityStore(securePreferencesStore)
    val relayCredentialStore = RelayCredentialStore(securePreferencesStore)
    val relayHttpClient = RelayHttpClient(okHttpClient, json)
    val relayWebSocketClient = RelayWebSocketClient(okHttpClient, json)
    val clipboardReadGateway = AndroidClipboardReadGateway(appContext)
    val clipboardApplyGateway = AndroidClipboardApplyGateway(
        context = appContext,
        providerAuthority = "${appContext.packageName}.clipboard",
    )
    val bridgeRuntime = BridgeRuntime(
        appContext = appContext,
        parentScope = appScope,
        json = json,
        deviceIdentityStore = deviceIdentityStore,
        relayCredentialStore = relayCredentialStore,
        relayWebSocketClient = relayWebSocketClient,
        envelopeCipher = envelopeCipher,
        clipboardApplyGateway = clipboardApplyGateway,
    )
    val notificationForwarder = NotificationForwarder(
        outboundSink = bridgeRuntime,
        noiseFilter = NotificationNoiseFilter(appContext.packageName),
        normalizer = NotificationPayloadNormalizer(appContext),
        scope = appScope,
    )
    val clipboardSyncCoordinator = ClipboardSyncCoordinator(
        readGateway = clipboardReadGateway,
        applyGateway = clipboardApplyGateway,
        outboundSink = bridgeRuntime,
        scope = appScope,
    )
    val pairingRepository = PairingRepository(
        appContext = appContext,
        relayHttpClient = relayHttpClient,
        deviceIdentityStore = deviceIdentityStore,
        relayCredentialStore = relayCredentialStore,
        sessionKeyStore = sessionKeyStore,
        envelopeCipher = envelopeCipher,
    )

    init {
        BridgeFeatureRegistry.notificationServiceDelegate = notificationForwarder
        BridgeFeatureRegistry.foregroundServiceDelegate = bridgeRuntime
    }
}
