package com.ht.stream

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.ht.stream.capture.CaptureVpnService
import com.ht.stream.proxy.CertAuthority
import com.ht.stream.window.FloatingWindowService

class StreamApp : Application() {

    /** 当前处于前台（可见）的 Activity 数量；0 表示 App 整体在后台 */
    private var foregroundActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        // 后台线程预生成/加载 CA，避免首次握手卡顿
        Thread { CertAuthority.ensureCa(this) }.start()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                foregroundActivityCount++
                // 窗口化模式下，抓包 App 自身切回前台 → 自动停止抓包：
                // 悬浮窗本就是给「App 在后台、用户在别的 App 里」用的，回到本 App 说明要看面板/配置，
                // 继续抓包没必要（且本 App 流量本就绕过 VPN，不会自己抓自己）。
                if (foregroundActivityCount == 1 &&
                    FloatingWindowService.isActive &&
                    CaptureVpnService.running.value
                ) {
                    stopCapture()
                }
            }

            override fun onActivityStopped(activity: Activity) {
                foregroundActivityCount = (foregroundActivityCount - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** 发送停止指令给抓包 VPN 服务（与 MainActivity 中的停止入口一致） */
    private fun stopCapture() {
        runCatching {
            startService(
                Intent(this, CaptureVpnService::class.java)
                    .setAction(CaptureVpnService.ACTION_STOP)
            )
        }
    }
}
