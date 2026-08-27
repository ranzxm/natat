package com.natat.tunnel

import hev.htproxy.TProxyService
import java.io.File

object NativeTunnel {
    fun start(configFile: File, tunFd: Int): Boolean = TProxyService.start(configFile.absolutePath, tunFd)

    fun stop(): Boolean = TProxyService.stop()

    fun isRunning(): Boolean = TProxyService.isRunning()

    fun stats(): LongArray = TProxyService.stats()
}
