class First {
    val x: Int

    init {
        use(this) // NPE! Leaking this
        x = foo() // NPE! Own function
    }

    fun foo() = x
}

fun use(first: First) = first.x.hashCode()

abstract class Second {
    val x: Int

    init {
        use(this) // Leaking this in non-final
        x = bar() // Own function in non-final
        foo()     // Non-final function call
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
        x = 42 // Non-final property access
    }
}

class ThirdDerived : Third() {
    override var x: Int
        set(arg) { field = arg + y }

    val y = 1
}

class Fourth {
    val x: Int
        get() = y

    val y = x // null!
}
