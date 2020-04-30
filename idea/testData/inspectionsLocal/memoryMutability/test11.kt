// PROBLEM: "Trying mutate frozen object"
// FIX: none
class Foo {
    var i = 0

    fun increment() {
        this.freeze()
        i<caret>++
    }

    fun freeze() { }
}
