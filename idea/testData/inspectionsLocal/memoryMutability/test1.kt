// PROBLEM: "Trying mutate frozen Object property"
// FIX: none
object Singleton {
    var i = 0
    fun increment() { i<caret>++ }
}
}