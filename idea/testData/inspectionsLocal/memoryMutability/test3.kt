// PROBLEM: Trying mutate frozen object
// FIX: none

object Singleton {
    var i = 0
}

fun main() {
    Singleton.i <caret>= 3
}
