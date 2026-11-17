package com.example.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

@Volatile
private var appCheckConfigured = false
private val initLock = Any()

/**
 * Garantit que [FirebaseApp] est initialisé dans le processus courant, même si
 * [com.google.firebase.provider.FirebaseInitProvider] n'a pas pu résoudre les options automatiquement.
 */
fun ensureFirebaseAppInitialized(context: Context): Boolean {
    val appContext = context.applicationContext ?: context
    synchronized(initLock) {
        if (FirebaseApp.getApps(appContext).isNotEmpty()) {
            return true
        }

        val defaultApp = runCatching { FirebaseApp.initializeApp(appContext) }.getOrNull()
        if (defaultApp != null || FirebaseApp.getApps(appContext).isNotEmpty()) {
            return true
        }

        val appId = readFirebaseStringResource(appContext, "google_app_id")
        val apiKey = readFirebaseStringResource(appContext, "google_api_key")
        val projectId = readFirebaseStringResource(appContext, "project_id")
        val senderId = readFirebaseStringResource(appContext, "gcm_defaultSenderId")
        val storageBucket = readFirebaseStringResource(appContext, "google_storage_bucket")

        if (!appId.isNullOrBlank() && !apiKey.isNullOrBlank()) {
            val builder = FirebaseOptions.Builder()
                .setApplicationId(appId)
                .setApiKey(apiKey)
            if (!projectId.isNullOrBlank()) builder.setProjectId(projectId)
            if (!senderId.isNullOrBlank()) builder.setGcmSenderId(senderId)
            if (!storageBucket.isNullOrBlank()) builder.setStorageBucket(storageBucket)
            runCatching { FirebaseApp.initializeApp(appContext, builder.build()) }
                .onFailure { Log.e(TAG, "Échec de l'initialisation explicite de FirebaseApp", it) }
        } else {
            Log.e(
                TAG,
                "Ressources Firebase absentes (google_app_id=${!appId.isNullOrBlank()}, google_api_key=${!apiKey.isNullOrBlank()})."
            )
        }

        return FirebaseApp.getApps(appContext).isNotEmpty()
    }
}

fun isFirebaseAppReady(context: Context): Boolean {
    val appContext = context.applicationContext ?: context
    return FirebaseApp.getApps(appContext).isNotEmpty() || ensureFirebaseAppInitialized(appContext)
}

private fun readFirebaseStringResource(context: Context, name: String): String? {
    val packagesToTry = linkedSetOf(
        context.packageName,
        "com.example",
        context.applicationInfo?.packageName
    ).filterNotNull().filter { it.isNotBlank() }

    for (pkg in packagesToTry) {
        val resId = context.resources.getIdentifier(name, "string", pkg)
        if (resId != 0) {
            val value = runCatching { context.getString(resId) }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
    }
    return null
}

/**
 * Configure Firebase App Check avant tout affichage Compose ou appel à Firebase.ai.
 * En mode Debug, persiste le jeton de débogage de façon synchrone (.commit()) depuis l'Intent.
 */
fun configureAppCheck(context: Context, intent: Intent?) {
    val appContext = context.applicationContext ?: context
    try {
        if (!ensureFirebaseAppInitialized(appContext)) {
            Log.e(TAG, "FirebaseApp non initialisé : vérifiez app/google-services.json lors de la compilation.")
            return
        }

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = FirebaseApp.getInstance().persistenceKey
                val prefs = appContext.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                // Écriture synchrone obligatoire avant DebugAppCheckProviderFactory.getInstance()
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(TAG, "Jeton App Check debug configuré.")
            } else if (!appCheckConfigured) {
                Log.w(TAG, "Aucun jeton debug App Check trouvé dans l'Intent de lancement.")
            }

            if (!appCheckConfigured) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                appCheckConfigured = true
            }
        } else if (!appCheckConfigured) {
            val siteKey = readFirebaseStringResource(appContext, RECAPTCHA_SITE_KEY_RES)
            if (!siteKey.isNullOrBlank()) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                )
                appCheckConfigured = true
            } else {
                Log.e(TAG, "Ressource recaptcha_site_key introuvable ou vide.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Échec de l'initialisation Firebase App Check", e)
    }
}
