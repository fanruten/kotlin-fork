// PROBLEM: Trying mutate frozen object
// FIX: none
class Foo {
    var i = 1

    fun increment() {
        freeze()
        mutate<caret>()
    }

    fun mutate() {
        i += 3
    }

    fun freeze() { }
}
