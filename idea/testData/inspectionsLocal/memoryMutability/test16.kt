// PROBLEM: Trying mutate frozen object
// FIX: none
class Foo {
    var i = 0

    fun method1() {
        freeze()
        method2<caret>()
    }

    fun method2() {
        i = 3
    }

    fun freeze() { }
}