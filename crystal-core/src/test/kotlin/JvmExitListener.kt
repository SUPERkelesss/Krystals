import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener

/**
 * Forces the test JVM to exit after the JUnit 5 launcher session closes.
 *
 * JUnit 5's Launcher may leave non-daemon threads (e.g., from the platform's
 * internal ExecutorService or from test code using parallelStream/CompletableFuture)
 * that prevent the forked test JVM from shutting down. This listener calls
 * [Runtime.exit] when the session closes, ensuring Gradle's Test task completes
 * promptly instead of hanging indefinitely.
 *
 * Registered via META-INF/services/org.junit.platform.launcher.LauncherSessionListener
 */
class JvmExitListener : LauncherSessionListener {
    override fun launcherSessionClosed(session: LauncherSession) {
        // Give Gradle a short grace period to collect test results via its
        // daemon socket before forcibly terminating the forked test JVM.
        // Some tests leave non-daemon threads (e.g. executor services that
        // were never shut down) which prevent the JVM from exiting on its
        // own, causing the Gradle Test task to hang indefinitely.
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
