package com.blockchain.remoteconfig

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import piuk.blockchain.androidcore.data.api.EnvironmentConfig
import piuk.blockchain.androidcore.utils.extensions.then

interface ABTestExperiment {
    fun getABVariant(key: String): Single<String>
}

interface RemoteConfig {

    fun getIfFeatureEnabled(key: String): Single<Boolean>

    fun getRawJson(key: String): Single<String>

    fun getFeatureCount(key: String): Single<Long>
}

class RemoteConfiguration(
    private val remoteConfig: FirebaseRemoteConfig,
    private val environmentConfig: EnvironmentConfig
) : RemoteConfig, ABTestExperiment {

    val timeout: Long
        get() = if (environmentConfig.isRunningInDebugMode()) 0L else 14400L

    private val configuration: Single<FirebaseRemoteConfig> =
        fetchRemoteConfig()
            .then {
                activate().onErrorComplete()
            }
            .cache()
            .toSingle { remoteConfig }

    private fun fetchRemoteConfig(): Completable {
        return Completable.create { emitter ->
            remoteConfig.fetch(timeout).addOnCompleteListener {
                if (!emitter.isDisposed)
                    emitter.onComplete()
            }
                .addOnFailureListener {
                    if (!emitter.isDisposed)
                        emitter.onError(it)
                }
        }
    }

    private fun activate(): Completable {
        return Completable.create { emitter ->
            remoteConfig.activate().addOnCompleteListener {
                if (!emitter.isDisposed)
                    emitter.onComplete()
            }
                .addOnFailureListener {
                    if (!emitter.isDisposed)
                        emitter.onError(it)
                }
        }.onErrorComplete()
    }

    override fun getRawJson(key: String): Single<String> =
        configuration.map {
            it.getString(key)
        }

    override fun getIfFeatureEnabled(key: String): Single<Boolean> =
        configuration.map { it.getBoolean(key) }

    override fun getABVariant(key: String): Single<String> =
        configuration.map { it.getString(key) }

    override fun getFeatureCount(key: String): Single<Long> =
        configuration.map { it.getLong(key) }
}

fun RemoteConfig.featureFlag(key: String): FeatureFlag = object :
    FeatureFlag {
    override val enabled: Single<Boolean> get() = getIfFeatureEnabled(key)
}
