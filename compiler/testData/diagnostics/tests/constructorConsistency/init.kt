class My {
    val x: Int

    init {
        x = <!DEBUG_INFO_LEAKING_THIS!>foo<!>()
    }

    fun foo(): Int = x
}