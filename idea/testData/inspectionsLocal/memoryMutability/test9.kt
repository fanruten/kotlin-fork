// PROBLEM: none
// FIX: none

class Foo {
    var i = 0
    fun freeze() { }
}

class Container {
    var foo = Foo()
    var m = 1
    fun create(): Foo {
        return Foo()
    }
}

fun main() {
    var c = Container()
    c.create().freeze()
    c.create().i <caret>= 3
}
