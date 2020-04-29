// PROBLEM: none
// FIX: none

class Container {
    fun freeze() { }
    var c1 = Container()
    var c2 = Container()
}

fun main() {
    var c = Container()
    c.c1.freeze()
    c.c2.c1 <caret>= Container()
}
