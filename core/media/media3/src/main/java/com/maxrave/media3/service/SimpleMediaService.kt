package com.maxrave.media3.service

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.common.MEDIA_NOTIFICATION
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.repository.ControlTarget
import com.maxrave.domain.repository.RefineControlRepository
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.extension.toCommandButton
import com.maxrave.media3.utils.CoilBitmapLoader
import com.maxrave.media3.utils.sizeLimitedForSession
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

@UnstableApi
internal class SimpleMediaService :
    MediaLibraryService(),
    KoinComponent {
    private val player: Player by inject<Player>(qualifier = named(com.maxrave.common.Config.MAIN_PLAYER))
    private val coilBitmapLoader: CoilBitmapLoader by inject<CoilBitmapLoader>()

    private var mediaSession: MediaLibrarySession? = null

    private val simpleMediaSessionCallback: MediaLibrarySession.Callback by inject<MediaLibrarySession.Callback>()

    private val simpleMediaServiceHandler: MediaPlayerHandler by inject<MediaPlayerHandler>(org.koin.core.qualifier.named(com.maxrave.common.Config.LOCAL_PLAYER))

    private val refineControl: RefineControlRepository by inject<RefineControlRepository>()
    private val serviceScope: CoroutineScope by inject<CoroutineScope>(named(com.maxrave.common.Config.SERVICE_SCOPE))
    private var remotePlayer: RemoteSessionPlayer? = null
    private var routeJob: Job? = null
    private var localButtons: List<GenericCommandButton> = emptyList()

    private val binder = MusicBinder()

    private var selfControllerFuture: ListenableFuture<MediaController>? = null

    inner class MusicBinder : Binder() {
        val service: SimpleMediaService
            get() = this@SimpleMediaService

        fun setActivitySession(
            context: Context,
            activity: Class<out Activity>,
        ) {
            mediaSession?.setSessionActivity(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, activity),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Logger.w("Service", "Simple Media Service Bound")
        return super.onBind(intent) ?: binder
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        Logger.w("Service", "Simple Media Service Created")

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { MEDIA_NOTIFICATION.NOTIFICATION_ID },
                MEDIA_NOTIFICATION.NOTIFICATION_CHANNEL_ID,
                R.string.notification_channel_name,
            ).apply {
                setSmallIcon(R.drawable.mono)
            },
        )

        if (mediaSession == null) {
            mediaSession =
                provideMediaLibrarySession(
                    this,
                    player,
                    simpleMediaSessionCallback,
                )
        }

        simpleMediaServiceHandler.onUpdateNotification = { list ->
            localButtons = list
            if (mediaSession?.player !is RemoteSessionPlayer) setButtons(list)
        }
        routeSessionToControlledDevice()

        val sessionToken = SessionToken(this, ComponentName(this, SimpleMediaService::class.java))
        selfControllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        getSystemService(NotificationManager::class.java)?.deleteNotificationChannel("media_playback_channel")
    }

    @UnstableApi
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Logger.w("Service", "Simple Media Service Received Action: ${intent?.action}")
        return super.onStartCommand(intent, flags, startId)
    }

    private fun setButtons(list: List<GenericCommandButton>) {
        mediaSession?.setMediaButtonPreferences(list.map { it.toCommandButton(this) })
    }

    private fun routeSessionToControlledDevice() {
        if (!refineControl.isSupported) return
        val remote = RemoteSessionPlayer(refineControl, serviceScope).also { remotePlayer = it }
        routeJob =
            serviceScope.launch {
                combine(refineControl.target, refineControl.remote) { target, playback ->
                    if (target is ControlTarget.Remote && playback != null && playback.connected && playback.track != null) {
                        Triple(playback.liked, playback.shuffle, playback.repeat)
                    } else {
                        null
                    }
                }.distinctUntilChanged().collect { buttons ->
                    val session = mediaSession ?: return@collect
                    if (buttons == null) {
                        if (session.player !== player) {
                            Logger.w("Service", "Media session back on this device")
                            session.player = player
                            setButtons(localButtons)
                        }
                        return@collect
                    }
                    if (session.player !== remote) {
                        Logger.w("Service", "Media session follows the controlled device")
                        session.player = remote
                    }
                    setButtons(
                        listOf(
                            GenericCommandButton.Like(buttons.first),
                            GenericCommandButton.Shuffle(isShuffled = buttons.second),
                            GenericCommandButton.Repeat(repeatState = buttons.third),
                        ),
                    )
                }
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    @UnstableApi
    private fun releaseServiceResources() {
        routeJob?.cancel()
        routeJob = null
        mediaSession?.let { if (it.player !== player) it.player = player }
        remotePlayer?.release()
        remotePlayer = null
        selfControllerFuture?.let { MediaController.releaseFuture(it) }
        selfControllerFuture = null
        mediaSession?.release()
        mediaSession = null
    }

    @UnstableApi
    private fun releaseEverythingAndExit() {
        Logger.w("Service", "Releasing playback and exiting")
        try {
            player.pause()
            player.playWhenReady = false
            releaseServiceResources()
            simpleMediaServiceHandler.release()
        } catch (e: Exception) {
            Logger.e("Service", "Error during release: ${e.message}")
        }
        exitProcess(0)
    }

    @UnstableApi
    override fun onDestroy() {
        Logger.w("Service", "Simple Media Service Destroyed")
        val killOnExit = simpleMediaServiceHandler.shouldReleaseOnTaskRemoved()
        releaseServiceResources()
        super.onDestroy()
        if (killOnExit) releaseEverythingAndExit()
    }

    override fun onTrimMemory(level: Int) {
        Logger.w("Service", "Simple Media Service Trim Memory Level: $level")
        simpleMediaServiceHandler.mayBeSaveRecentSong()
    }

    @UnstableApi
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.w("Service", "Simple Media Service Task Removed")
        simpleMediaServiceHandler.mayBeSaveRecentSong()
        if (simpleMediaServiceHandler.shouldReleaseOnTaskRemoved()) {
            super.onTaskRemoved(rootIntent)
            releaseEverythingAndExit()
        }
    }

    @UnstableApi
    private fun provideMediaLibrarySession(
        service: MediaLibraryService,
        player: Player,
        callback: MediaLibrarySession.Callback,
    ): MediaLibrarySession =
        MediaLibrarySession
            .Builder(
                service,
                player,
                callback,
            ).setId(this.javaClass.name)
            .setBitmapLoader(sizeLimitedForSession(service, coilBitmapLoader))
            // The launcher activity is resolved at runtime, so this module never has to name MainActivity.
            .apply {
                service.packageManager.getLaunchIntentForPackage(service.packageName)?.let { launch ->
                    setSessionActivity(
                        PendingIntent.getActivity(service, 0, launch, PendingIntent.FLAG_IMMUTABLE),
                    )
                }
            }
            .build()
}