// PROBLEM: Trying mutate frozen object
// FIX: none
class Bar {
    var i = 0

    fun method1() {
        i = 3
    }

    fun update(): Bar {
        i = 3
        return this
    }

    fun freeze() { }
}

class Foo {
    var b = Bar()

    fun method1() {
        b.freeze()
        b = b.update<caret>()
    }

    fun freeze() { }
}