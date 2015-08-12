class My(var x: Int) {    

    var y: Int
        get() = if (x > 0) x else z
        set(arg: Int) {
            if (arg > 0) x = arg
        }

    val z: Int

    init {
        // Dangerous: getter!
        if (<!DEBUG_INFO_LEAKING_THIS!>y<!> > 0) z = this.<!DEBUG_INFO_LEAKING_THIS!>y<!>
        // Dangerous: setter!
        <!DEBUG_INFO_LEAKING_THIS!>y<!> = 42
        <!VAL_REASSIGNMENT!>z<!> = -1
    }
}
