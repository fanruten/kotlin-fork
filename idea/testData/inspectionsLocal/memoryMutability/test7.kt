// PROBLEM: Trying mutate frozen object
// FIX: none

class Foo {
    var i = 0
    fun freeze() { }
}

class Container {
    var foo = Foo()
    var m = 1
}

fun main() {
    var c = Container()
    c.foo.freeze()
    c.foo.i <caret>+= 5
}
