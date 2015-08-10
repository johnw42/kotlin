open class Base(val x: Int) {
    fun foo() = x + 1
}

class Derived(x: Int): Base(x) {
    // It's still dangerous: we're not sure that foo() does not call some open function inside
    val y = <!DEBUG_INFO_LEAKING_THIS!>foo<!>()
    val z = x - 1
}
