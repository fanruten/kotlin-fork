// PROBLEM: none
// FIX: none
class Foo {
    var i = 1

    fun increment() {
        freeze()
        mutate<caret>()
    }

    fun mutate() {
        var i = 5
        i = 3
    }

    fun freeze() { }
}
