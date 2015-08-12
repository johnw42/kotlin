class My {
    val x: Int
        get() = field + z

    val y: Int
        get() = field - z

    val w: Int

    init {
        // Safe, val never has a setter
        x = 0
        this.y = 0
        // Unsafe
        w = this.<!DEBUG_INFO_LEAKING_THIS!>x<!> + <!DEBUG_INFO_LEAKING_THIS!>y<!>
    }

    val z = 1
}
