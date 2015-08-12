class My(val v: Int) {
    // Ok: setter is just private
    var x: Int
        private set

    <!MUST_BE_INITIALIZED!>var y: Int<!>
        set(arg) { field = arg }

    <!MUST_BE_INITIALIZED!>var z: Int<!>
        set(arg) { field = arg }

    // Ok: initializer available
    var w: Int = v
        set(arg) { field = arg }

    // Ok: no backing field
    var u: Int
        get() = w
        set(arg) { w = 2 * arg }

    constructor(): this(0) {
        z = v        
    }

    init {
        <!DEBUG_INFO_LEAKING_THIS!>x<!> = 1
        <!DEBUG_INFO_LEAKING_THIS!>y<!> = 2
        <!DEBUG_INFO_LEAKING_THIS!>u<!> = 3
    }
}
