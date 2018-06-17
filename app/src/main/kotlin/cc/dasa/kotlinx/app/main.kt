package cc.dasa.kotlinx.app

import cc.dasa.kotlinx.scratch.anim.*
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.util.concurrent.atomic.AtomicInteger

fun main(args: Array<String>) {
    launch { work() }
    Thread.sleep(30000L)
}

suspend fun work() {
    val w = Worker()
    w.start({
        tick = 1000
        dur = 10000

        at = 10.0
        to = 0.0

        notify = AtomicInteger()
        update = {
            println("notify: ${notify?.get()}, pt: ${kotlin.math.round(pt)}")
        }
    })
    launch { change(w) }
}

suspend fun change(w: Worker) {
    delay(5500)
    w.start({
        dur = 5000
        to = 10.0
    })
}
