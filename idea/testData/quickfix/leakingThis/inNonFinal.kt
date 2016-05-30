// "Make 'My' final" "true"

abstract class My {
    init {
        register(<caret>this)
    }
}

fun register(my: My) {}