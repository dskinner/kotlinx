package cc.dasa.kotlinx.scratch.anim

import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.NonCancellable.cancel
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.selects.select
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.exp

/** Ticker ticks at regular intervals, sending the tick time in milliseconds on [C]. */
class Ticker internal constructor(val C: ReceiveChannel<Long>, internal val job: Job)

/** Returns a new [Ticker] that starts immediately with duration [dur]. */
fun Ticker(dur: Long): Ticker {
    if (dur <= 0) {
        throw IllegalArgumentException("Ticker duration <= 0 not allowed: $dur")
    }
    val chan = Channel<Long>(1)
    val job = launch {
        while (true) {
            delay(dur)
            chan.send(System.currentTimeMillis())
        }
    }
    return Ticker(chan, job)
}

/** Stop generating ticks but leaves channel open. */
fun Ticker.stop() {
    job.cancel()
}


// ---------------------------------------------------------------------------


/** Linear Decay. */
private val lindecay = { t: Double -> 1 - t }

private val lindrive = { t: Double -> t }

/** Exponential decay. */
private val expdecay = { t: Double -> 1 - exp(2 * PI * -t) }

private val expdrive = { t: Double -> exp(2 * PI * -t) }

/** Linear interpolation. */
private fun lerp(a: Double, b: Double, t: Double): Double {
    return a + (t * (b - a))
}


// ---------------------------------------------------------------------------


/** Worker performs work at regular interval steps until complete. */
class Worker internal constructor(
        var at: Double = 0.0,
        var pt: Double = 0.0,
        var to: Double = 0.0,
        var interp: (Double) -> Double = lindrive,

        var epoch: Long = 0,
        var dur: Long = 0,
        var tick: Long,

        var update: Worker.() -> Unit = {},
        var notify: AtomicInteger? = null,
        internal var die: Channel<Unit>,
        internal var done: Channel<Unit>
)

/** Returns new [Worker] that will tick every 16ms once started. */
fun Worker(): Worker {
    val w = Worker(
            tick = 16L,
            die = Channel(),
            done = Channel())
    w.die.close()
    w.done.close()
    return w
}

/** Notify [Worker] to stop at next interval. */
suspend fun Worker.cancel() {
    select<Unit> {
        done.onReceiveOrNull { }
        onTimeout(0) {
            die.close()
            done.receiveOrNull()
        }
    }
}

/** Performs work for [Worker] at regular interval steps until complete, or told to die. */
private suspend fun Worker._start() {
    if (0L == dur) {
        at = to
        pt = to
        end()
        done.close()
        return
    }
    val ticker = Ticker(tick)
    var ok = true
    while (ok) {
        select<Unit> {
            ticker.C.onReceive {
                if (!step(it)) {
                    ticker.stop()
                    end()
                    done.close()
                    ok = false // return
                }
                update()
            }
            die.onReceiveOrNull {
                ticker.stop()
                end()
                done.close()
                ok = false // return
            }
        }
    }
}

/** Step and return true if ok. */
fun Worker.step(now: Long): Boolean {
    if (0L == epoch) {
        epoch = now
        return true
    }
    val since = now - epoch
    val ok = since < dur
    if (ok) {
        val delta = interp(since.toDouble() / dur.toDouble())
        pt = lerp(at, to, delta)
    } else {
        at = to
        pt = to
    }
    return ok
}

/** Update notify as part of finalizing work has completed. */
private fun Worker.end() {
    if (null != notify) {
        notify?.decrementAndGet()
    }
}

/** Counter-part to [cancel] for finalizing interrupted work followed by staging new work. */
private suspend fun Worker.listen() {
    select<Unit> {
        die.onReceiveOrNull {
            end()
            done.close()
        }
    }
}

/** Interrupts current work to stage new work at current [Worker.pt]. */
private suspend fun Worker._stage(epoch: Long, vararg transforms: Worker.() -> Unit) {
    if (null != notify) {
        notify?.incrementAndGet()
    }
    cancel()
    die = Channel()
    done = Channel()

    this.epoch = epoch
    at = pt
    to = pt

    for (transform in transforms) {
        transform(this)
    }
}

/** Stage stops previous work, and stages new jobs. */
suspend fun Worker.stage(epoch: Long, vararg transforms: Worker.() -> Unit) {
    _stage(epoch, *transforms)
    launch { listen() }
}

/** Start stops previous work, stages new jobs, and starts work immediately. */
suspend fun Worker.start(vararg transforms: Worker.() -> Unit) {
    _stage(System.currentTimeMillis(), *transforms)
    launch { _start() }
}