class My {
    val x: Int

    constructor() {
        val temp = <!DEBUG_INFO_LEAKING_THIS!>this<!>
        x = bar(temp)
    }

}

fun bar(arg: My): Int = arg.x
