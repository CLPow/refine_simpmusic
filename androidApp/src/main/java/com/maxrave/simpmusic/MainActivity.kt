package com.maxrave.simpmusic

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.eygraber.uri.toKmpUriOrNull
import com.maxrave.common.FIRST_TIME_MIGRATION
import com.maxrave.common.STATUS_DONE
import com.maxrave.common.SUPPORTED_LOCATION
import com.maxrave.domain.data.model.intent.GenericIntent
import com.maxrave.domain.data.player.AppOnScreen
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.logger.Logger
import com.maxrave.media3.di.setServiceActivitySession
import com.maxrave.simpmusic.di.viewModelModule
import com.maxrave.simpmusic.utils.ComposeResUtils
import com.maxrave.simpmusic.utils.VersionManager
import com.maxrave.simpmusic.viewModel.SharedViewModel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.dsl.module
import org.simpmusic.crashlytics.pushPlayerError
import pub.devrel.easypermissions.EasyPermissions
import java.util.Locale
import kotlinx.coroutines.runBlocking

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {
    val viewModel: SharedViewModel by inject()
    val mediaPlayerHandler by inject<MediaPlayerHandler>()
    val dataStoreManager: DataStoreManager by inject()

    private var mBound = false
    private var shouldUnbind = false
    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                setServiceActivitySession(this@MainActivity, MainActivity::class.java, service)
                Logger.w("MainActivity", "onServiceConnected: ")
                mBound = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Logger.w("MainActivity", "onServiceDisconnected: ")
                mBound = false
            }
        }

    override fun onStart() {
        super.onStart()
        AppOnScreen.set(true)
        startMusicService()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) AppOnScreen.set(false)
        if (shouldUnbind) {
            unbindService(serviceConnection)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Logger.d("MainActivity", "onNewIntent: $intent")
        viewModel.setIntent(
            GenericIntent(
                action = intent.action,
                data = (intent.data ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.toUri())?.toKmpUriOrNull(),
                type = intent.type,
            ),
        )
    }

    @ExperimentalFoundationApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadKoinModules(
            module {
                single<AppCompatActivity> { this@MainActivity }
            },
        )
        unloadKoinModules(viewModelModule)
        loadKoinModules(viewModelModule)
        VersionManager.initialize()
        if (viewModel.recreateActivity.value || viewModel.isServiceRunning) {
            viewModel.activityRecreateDone()
        } else {
            startMusicService()
        }
        Logger.d("MainActivity", "onCreate: ")
        val data = (intent?.data ?: intent?.getStringExtra(Intent.EXTRA_TEXT)?.toUri())?.toKmpUriOrNull()
        if (data != null) {
            viewModel.setIntent(
                GenericIntent(
                    action = intent.action,
                    data = data,
                    type = intent.type,
                ),
            )
        }
        if (getString(FIRST_TIME_MIGRATION) != STATUS_DONE) {
            if (SUPPORTED_LOCATION.items.contains(Locale.getDefault().country)) {
                putString("location", Locale.getDefault().country)
            } else {
                // Never default to the IN region.
                putString("location", "US")
            }
            putString(FIRST_TIME_MIGRATION, STATUS_DONE)
        }
        if (!AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }

        enableEdgeToEdge(
            navigationBarStyle =
                SystemBarStyle.dark(
                    scrim = Color.Transparent.toArgb(),
                ),
            statusBarStyle =
                SystemBarStyle.dark(
                    scrim = Color.Transparent.toArgb(),
                ),
        )
        viewModel.checkIsRestoring()
        WorkManager.getInstance(this).cancelUniqueWork("Artist Worker")
        WorkManager.getInstance(this).cancelUniqueWork("Blog RSS Worker")
        WorkManager.getInstance(this).cancelUniqueWork("AutoBackupWorker")

        if (!EasyPermissions.hasPermissions(this, Manifest.permission.POST_NOTIFICATIONS)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lifecycleScope.launch {
                    val doNotAsk = viewModel.readString("notification_permission_do_not_ask")
                    if (doNotAsk != "true") {
                        val wasAsked = viewModel.readString("notification_permission_asked")
                        if (wasAsked != "true") {
                            EasyPermissions.requestPermissions(
                                this@MainActivity,
                                ComposeResUtils.getResString(ComposeResUtils.StringType.NOTIFICATION_REQUEST),
                                1,
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                            putString("notification_permission_asked", "true")
                        } else {
                            // Already asked before: show custom dialog with "Don't show again"
                            viewModel.showNotificationPermissionDialog()
                        }
                    }
                }
            }
        }
        viewModel.getLocation()

        setContent {
            App(viewModel)
        }
    }

    override fun onDestroy() {
        val shouldStopMusicService = viewModel.shouldStopMusicService()
        Logger.w("MainActivity", "onDestroy: Should stop service $shouldStopMusicService")

        if (shouldStopMusicService && shouldUnbind && isFinishing) {
            viewModel.isServiceRunning = false
        }
        unloadKoinModules(viewModelModule)
        super.onDestroy()
        Logger.d("MainActivity", "onDestroy: ")
    }

    override fun onRestart() {
        super.onRestart()
        viewModel.activityRecreate()
    }

    private fun startMusicService() {
        com.maxrave.media3.di
            .startService(this@MainActivity, serviceConnection)
        mediaPlayerHandler.pushPlayerError = { it ->
            pushPlayerError(it)
        }
        mediaPlayerHandler.showToast = { type ->
            lifecycleScope.launch {
                viewModel.makeToast(
                    when (type) {
                        is ToastType.ExplicitContent ->
                            ComposeResUtils.getResString(ComposeResUtils.StringType.EXPLICIT_CONTENT_BLOCKED)
                        is ToastType.TrackSkipped ->
                            ComposeResUtils.getResString(ComposeResUtils.StringType.TRACK_SKIPPED)
                        is ToastType.WifiOnly ->
                            ComposeResUtils.getResString(ComposeResUtils.StringType.WIFI_ONLY)
                        is ToastType.PremiumOnly ->
                            ComposeResUtils.getResString(ComposeResUtils.StringType.PREMIUM_ONLY)
                        is ToastType.PlayerError ->
                            ComposeResUtils.getResString(
                                if (type.network) ComposeResUtils.StringType.TIME_OUT_ERROR else ComposeResUtils.StringType.PLAYBACK_ERROR,
                                type.error,
                            )
                    },
                )
            }
        }
        viewModel.isServiceRunning = true
        shouldUnbind = true
        Logger.d("Service", "Service started")
    }

    private fun putString(
        key: String,
        value: String,
    ) {
        viewModel.putString(key, value)
    }

    private fun getString(key: String): String? = runBlocking { viewModel.readString(key) }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        viewModel.activityRecreate()
    }
}