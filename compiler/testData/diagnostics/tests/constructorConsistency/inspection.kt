class First {
    val x: Int

    init {
        use(<!DEBUG_INFO_LEAKING_THIS!>this<!>) // NPE! Leaking this
        x = <!DEBUG_INFO_LEAKING_THIS!>foo<!>() // NPE! Own function
    }

    fun foo() = x
}

fun use(first: First) = first.x.hashCode()

abstract class Second {
    val x: Int

    init {
        use(<!DEBUG_INFO_LEAKING_THIS!>this<!>) // Leaking this in non-final
        x = <!DEBUG_INFO_LEAKING_THIS!>bar<!>() // Own function in non-final
        <!DEBUG_INFO_LEAKING_THIS!>foo<!>()     // Non-final function call
    }

    private fun bar() = foo()

    abstract fun foo(): Int
}

fun use(second: Second) = second.x

class SecondDerived : Second() {
    val y = x // null!

    override fun foo() = y
}

open class Third {
    open var x: Int

    constructor() {
        <!DEBUG_INFO_LEAKING_THIS!>x<!> = 42 // Non-final property access
    }
}

class ThirdDerived : Third() {
    <!MUST_BE_INITIALIZED!>override var x: Int<!>
        set(arg) { field = arg + y }

    val y = 1
}

class Fourth {
    val x: Int
        get() = y

    val y = <!DEBUG_INFO_LEAKING_THIS!>x<!> // null!
}
