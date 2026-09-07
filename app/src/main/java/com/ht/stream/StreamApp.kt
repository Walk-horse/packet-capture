package com.ht.stream

import android.app.Application
import com.ht.stream.proxy.CertAuthority

class StreamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 后台线程预生成/加载 CA，避免首次握手卡顿
        Thread { CertAuthority.ensureCa(this) }.start()
    }
}
