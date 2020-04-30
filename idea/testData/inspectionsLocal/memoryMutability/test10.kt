// PROBLEM: Trying mutate frozen object
// FIX: none
class Foo {
    var i = 0

    fun increment() {
        freeze()
        i<caret>++
    }

    fun freeze() { }
}
