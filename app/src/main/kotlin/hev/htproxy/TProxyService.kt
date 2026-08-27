package hev.htproxy

/** JNI surface required by the embedded hev-socks5-tunnel engine. */
object TProxyService {
    private external fun TProxyStartService(configPath: String, fd: Int): Boolean
    private external fun TProxyStopService(): Boolean
    private external fun TProxyIsRunning(): Boolean
    private external fun TProxyGetStats(): LongArray

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    fun start(configPath: String, fd: Int): Boolean = TProxyStartService(configPath, fd)

    fun stop(): Boolean = TProxyStopService()

    fun isRunning(): Boolean = TProxyIsRunning()

    fun stats(): LongArray = TProxyGetStats()
}
