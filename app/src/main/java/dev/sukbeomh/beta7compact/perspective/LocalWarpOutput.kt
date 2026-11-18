package dev.sukbeomh.beta7compact.perspective

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Treat cancellation after publish as a failed save too; never leave its output behind. */
internal suspend fun <T> publishLocalWarpOutput(
    create: () -> T,
    write: suspend (T) -> Unit,
    publish: (T) -> Unit,
    delete: (T) -> Unit,
    checkpoint: () -> Unit = {},
): T {
    coroutineContext.ensureActive()
    val output = create()
    try {
        coroutineContext.ensureActive()
        checkpoint()
        write(output)
        coroutineContext.ensureActive()
        publish(output)
        checkpoint()
        coroutineContext.ensureActive()
        return output
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            try { delete(output) }
            catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
        }
        throw failure
    }
}

/** Powers-of-two sampling uses ceiling bounds so even decoder rounding stays within the limit. */
internal fun localWarpSampleSize(width: Int, height: Int, maxSide: Int): Int {
    require(width > 0 && height > 0 && maxSide > 0)
    var sample = 1
    while ((maxOf(width, height).toLong() + sample - 1) / sample > maxSide) {
        check(sample <= Int.MAX_VALUE / 2)
        sample *= 2
    }
    return sample
}
