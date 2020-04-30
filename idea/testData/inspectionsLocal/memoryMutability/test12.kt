// PROBLEM: "Trying mutate frozen object"
// FIX: none
class Data {
    var n = 0

    fun freeze() { }
}

class Foo {
    var i = Data()

    fun increment() {
        this.i.freeze()
        i.n<caret>++
    }

    fun freeze() { }
}
