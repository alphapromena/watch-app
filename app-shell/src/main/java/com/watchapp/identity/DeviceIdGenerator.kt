package com.watchapp.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlin.random.Random

/**
 * Produces and persists a stable 15-digit synthetic IMEI for this watch.
 *
 * Format:
 *  - digit 1     : `9`  (an unused GSMA TAC prefix — marks this device as synthetic)
 *  - digits 2-14 : random 0-9
 *  - digit 15    : Luhn check digit over digits 1-14
 *
 * Generated once on first run and stored encrypted; every subsequent
 * [getOrCreate] call returns the same value.
 */
class DeviceIdGenerator internal constructor(
    private val storage: ImeiStorage,
    private val random: Random = Random.Default,
) {

    /** Returns the persisted IMEI, generating and saving one on first call. */
    fun getOrCreate(): String {
        storage.read()?.let { return it }
        val imei = generate()
        storage.write(imei)
        return imei
    }

    private fun generate(): String {
        val prefix = buildString(14) {
            append('9')
            repeat(13) { append(random.nextInt(10)) }
        }
        return prefix + luhnCheckDigit(prefix)
    }

    /** Storage seam so the generator can be unit-tested without Android. */
    internal interface ImeiStorage {
        fun read(): String?
        fun write(imei: String)
    }

    companion object {
        const val PREFS_NAME = "device_identity"
        const val KEY_IMEI = "imei"

        /**
         * Computes the standard Luhn check digit for [prefix].
         * From the rightmost digit, every other digit is doubled (with 9 subtracted
         * if the result exceeds 9); the check is `(10 - sum mod 10) mod 10`.
         */
        fun luhnCheckDigit(prefix: String): Int {
            var sum = 0
            for ((distFromRight, char) in prefix.reversed().withIndex()) {
                var d = char.digitToInt()
                if (distFromRight % 2 == 0) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
            }
            return (10 - sum % 10) % 10
        }

        /** Production wiring: stores the IMEI in EncryptedSharedPreferences. */
        fun create(context: Context): DeviceIdGenerator {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return DeviceIdGenerator(SharedPreferencesImeiStorage(prefs))
        }
    }

    private class SharedPreferencesImeiStorage(
        private val prefs: SharedPreferences,
    ) : ImeiStorage {
        override fun read(): String? = prefs.getString(KEY_IMEI, null)
        override fun write(imei: String) {
            prefs.edit().putString(KEY_IMEI, imei).apply()
        }
    }
}
