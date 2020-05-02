// PROBLEM: Trying mutate frozen object
// FIX: none
class Foo {
    var i = 0

    fun method1() {
        method2()
        i <caret>= 3
    }

    fun method2() {
        freeze()
    }

    fun freeze() { }
}