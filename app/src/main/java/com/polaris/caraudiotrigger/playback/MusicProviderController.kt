package com.polaris.caraudiotrigger.playback

import android.content.Context
import com.polaris.caraudiotrigger.data.MusicProvider

data class MusicPlaybackResult(
    val statusMessage: String,
    val confirmed: Boolean,
    val terminal: Boolean,
)

data class MusicProviderDescriptor(
    val provider: MusicProvider,
    val packageName: String,
    val installUrl: String,
)

interface MusicProviderController {
    val provider: MusicProvider
    fun isInstalled(): Boolean
    fun hasControlAccess(): Boolean
    suspend fun resume(): MusicPlaybackResult
}

object MusicProviderRegistry {
    private val descriptors = listOf(
        MusicProviderDescriptor(
            provider = MusicProvider.APPLE_MUSIC,
            packageName = AppleMusicController.PACKAGE_NAME,
            installUrl = "https://play.google.com/store/apps/details?id=com.apple.android.music",
        ),
        MusicProviderDescriptor(
            provider = MusicProvider.SPOTIFY,
            packageName = "com.spotify.music",
            installUrl = "https://www.spotify.com/download/android/",
        ),
        MusicProviderDescriptor(
            provider = MusicProvider.QQ_MUSIC,
            packageName = "com.tencent.qqmusic",
            installUrl = "https://y.qq.com/download/download.html",
        ),
        MusicProviderDescriptor(
            provider = MusicProvider.NETEASE_CLOUD_MUSIC,
            packageName = "com.netease.cloudmusic",
            installUrl = "https://music.163.com/#/download",
        ),
        MusicProviderDescriptor(
            provider = MusicProvider.KUGOU_MUSIC,
            packageName = "com.kugou.android",
            installUrl = "https://download.kugou.com/android.html",
        ),
    )
    private val descriptorsByProvider = descriptors.associateBy(MusicProviderDescriptor::provider)

    val providers: List<MusicProvider> = descriptors.map(MusicProviderDescriptor::provider)

    fun descriptor(provider: MusicProvider): MusicProviderDescriptor =
        checkNotNull(descriptorsByProvider[provider]) { "Unsupported music provider: $provider" }

    fun create(context: Context, provider: MusicProvider): MusicProviderController =
        PlatformMusicProviderController(
            provider = provider,
            delegate = AppleMusicController(context, descriptor(provider)),
        )
}

private class PlatformMusicProviderController(
    override val provider: MusicProvider,
    private val delegate: AppleMusicController,
) : MusicProviderController {
    override fun isInstalled(): Boolean = delegate.isInstalled()

    override fun hasControlAccess(): Boolean = delegate.hasNotificationAccess()

    override suspend fun resume(): MusicPlaybackResult = delegate.resume().let { result ->
        MusicPlaybackResult(
            statusMessage = result.statusMessage(provider.displayName),
            confirmed = result.confirmed,
            terminal = result.terminal,
        )
    }
}

private fun AppleMusicResumeResult.statusMessage(providerName: String): String = when (this) {
    AppleMusicResumeResult.STARTED -> "$providerName 已开始播放"
    AppleMusicResumeResult.MEDIA_SERVICE_STARTED -> "已通过 $providerName 媒体服务恢复播放"
    AppleMusicResumeResult.SYSTEM_PLAY_STARTED -> "已通过系统播放命令恢复 $providerName"
    AppleMusicResumeResult.ALREADY_PLAYING -> "$providerName 已在播放"
    AppleMusicResumeResult.NOT_INSTALLED -> "手机未安装 $providerName"
    AppleMusicResumeResult.NOTIFICATION_ACCESS_REQUIRED ->
        "未授予媒体会话控制权，仍在强制尝试"
    AppleMusicResumeResult.NO_SESSION -> "尚未建立 $providerName 播放会话"
    AppleMusicResumeResult.AUDIO_NOT_ACTIVE -> "$providerName 显示播放但仍未输出声音"
    AppleMusicResumeResult.FAILED -> "$providerName 播放请求暂未生效"
}
