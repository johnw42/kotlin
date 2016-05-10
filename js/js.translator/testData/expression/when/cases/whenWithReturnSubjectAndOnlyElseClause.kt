// See KT-8318
package foo

var global = ""

fun log(x: Any?) {
    global += x.toString()
}

fun<T> foo(a: T, i: Int): T {
    log(i)
    return a
}

fun test(x: Int): Boolean {
    when (if (x == 4) return foo(true, 1) else foo(4, 3)) {
        else -> return foo(false, 2)
    }
}

fun box(): String {
    assertEquals(false, test(0))
    log(";")
    assertEquals(true, test(4))
    assertEquals("32;1", global)

    return "OK"
}