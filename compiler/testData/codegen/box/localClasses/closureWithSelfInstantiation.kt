// Enable for JVM backend when KT-8120 gets fixed
// TARGET_BACKEND: JS

fun box(): String {
    val capturedInConstructor = 1
    val capturedInBody = 10

    class A(var x: Int) {
        var y = 0

        fun copy(): A {
            val result = A(x)
            result.y += capturedInBody
            return result
        }

        init {
            y += x + capturedInConstructor
        }
    }

    val a = A(100).copy()
    if (a.y != 111) return "fail1a: ${a.y}"
    if (a.x != 100) return "fail1b: ${a.x}"


    class B(var x: Int) {
        var y = 0

        fun copier(): () -> B = {
            val result = B(x)
            result.y += capturedInBody
            result
        }

        init {
            y += x + capturedInConstructor
        }
    }

    val b = B(100).copier()()
    if (b.y != 111) return "fail2a: ${b.y}"
    if (b.x != 100) return "fail2b: ${b.x}"


    class C(var x: Int) {
        var y = 0

        inner class D() {
            fun copyOuter(): C {
                val result = C(x)
                result.y += capturedInBody
                return result
            }
        }

        init {
            y += x + capturedInConstructor
        }
    }

    return "OK"
}