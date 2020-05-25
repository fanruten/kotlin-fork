// PROBLEM: Trying mutate frozen object
// FIX: none
class Bar {
    var i = 0

    fun method1() {
        i = 3
    }

    fun freeze() { }
}

class Foo {
    var b = Bar()

    fun method1() {
        b.freeze()
        b.<caret>method1()
    }

    fun freeze() { }
}