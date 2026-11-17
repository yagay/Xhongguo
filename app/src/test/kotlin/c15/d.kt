package c15

class FakeSurface(private val available: Boolean) {
    fun isSurfaceAvailable(): Boolean = available
}

class d(
    @JvmField val b: Any,
    @JvmField val c: FakeSurface,
    @JvmField var i: Boolean = false
)
