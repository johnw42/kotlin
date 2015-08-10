class My(x: Int) {
    val y: Int = <!DEBUG_INFO_LEAKING_THIS!>foo<!>(x)

    fun foo(x: Int): Int = x + y
}