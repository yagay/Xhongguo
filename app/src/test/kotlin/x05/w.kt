package x05

class w(@JvmField val f: c15.d) {
    var playRequests = 0
    var queuedForSurface = 0

    fun resume() {
        if (f.c.isSurfaceAvailable() || f.i) playRequests++
        else queuedForSurface++
    }
}
