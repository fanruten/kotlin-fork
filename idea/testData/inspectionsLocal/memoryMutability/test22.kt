// PROBLEM: none
// FIX: none
class Bar {
    var i = 0

    fun freeze() { }
}

class Foo {
    var b = Bar()

    fun method1() {
        b.freeze()
        method2<caret>()
    }

    fun method2() {
        b = Bar()
        b.i = 1
    }

    fun freeze() { }
}