// PROBLEM: Trying mutate frozen object
// FIX: none
class Bar {
    var i = 0

    fun freeze() { }
}

class Foo {
    var b = Bar()

    fun method1() {
        b.freeze()
        method2()
        b.i <caret>= 3
    }

    fun method2() {
    }

    fun freeze() { }
}
