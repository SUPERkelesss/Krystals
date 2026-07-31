import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener

class JvmExitListener : LauncherSessionListener {
    override fun launcherSessionClosed(session: LauncherSession) {
        Thread {
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            Runtime.getRuntime().halt(0)
        }.apply {
            isDaemon = true
            start()
        }
    }
}
