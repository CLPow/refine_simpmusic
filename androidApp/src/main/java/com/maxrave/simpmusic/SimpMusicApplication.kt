package com.maxrave.simpmusic

import android.annotation.SuppressLint
import android.app.Application
import android.database.CursorWindow
import android.net.ConnectivityManager
import android.net.Network
import com.maxrave.domain.repository.AccountRepository
import org.koin.core.component.get
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Configuration
import androidx.work.WorkManager
import cat.ereza.customactivityoncrash.config.CaocConfig
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.maxrave.data.di.loader.loadAllModules
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.di.viewModelModule
import com.maxrave.simpmusic.ui.widget.TurntableWidget
import androidx.glance.appwidget.updateAll
import com.maxrave.simpmusic.utils.NetworkFirstInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import multiplatform.network.cmptoast.AppContext
import okhttp3.OkHttpClient
import okio.FileSystem
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.simpmusic.crashlytics.configCrashlytics
import java.lang.reflect.Field

class SimpMusicApplication :
    Application(),
    KoinComponent,
    SingletonImageLoader.Factory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.DEBUG) Logger.setMinLevel(com.maxrave.logger.LogLevel.WARN)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        configCrashlytics(this, BuildKonfig.sentryDsn)
        startKoin {
            androidLogger(level = Level.ERROR)
            androidContext(this@SimpMusicApplication)
            loadAllModules()
            loadKoinModules(viewModelModule)
        }
        val workConfig =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.INFO)
                .build()

        WorkManager.initialize(this, workConfig)

        applicationScope.launch {
            runCatching { TurntableWidget().updateAll(this@SimpMusicApplication) }
        }

        applicationScope.launch {
            runCatching { java.io.File(filesDir, "kuromoji-ipadic").deleteRecursively() }
            runCatching { java.io.File(filesDir, "spotifyCanvas").deleteRecursively() }
        }

        runCatching {
            val accounts = get<AccountRepository>()
            (getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = accounts.resetConnections()

                    override fun onLost(network: Network) = accounts.resetConnections()
                },
            )
        }

        CaocConfig.Builder
            .create()
            .backgroundMode(CaocConfig.BACKGROUND_MODE_SILENT)
            .enabled(true)
            .showErrorDetails(true)
            .showRestartButton(true)
            .errorDrawable(R.mipmap.ic_launcher_round)
            .logErrorOnRestart(false)
            .trackActivities(true)
            .minTimeBetweenCrashesMs(2000)
            .restartActivity(MainActivity::class.java)
            .apply()

        CrashLog.install(this)

        try {
            @SuppressLint("DiscouragedPrivateApi")
            val field: Field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            field.set(null, 100 * 1024 * 1024)
        } catch (e: Exception) {
            Logger.w("Startup", "CursorWindow size not raised: ${e.message}")
        }

        AppContext.apply {
            set(applicationContext)
        }
    }

    override fun onTerminate() {
        super.onTerminate()

        Logger.w("Terminate", "Checking")
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        lateinit var imageLoader: ImageLoader
        imageLoader =
            ImageLoader
                .Builder(context)
                .components {
                    add(NetworkFirstInterceptor { imageLoader })
                    add(
                        OkHttpNetworkFetcherFactory(
                            callFactory = {
                                OkHttpClient()
                            },
                        ),
                    )
                }.diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .diskCache(
                    DiskCache
                        .Builder()
                        .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "image_cache")
                        .maxSizeBytes(250L * 1024 * 1024)
                        .build(),
                ).crossfade(true)
                .build()
        return imageLoader
    }
}