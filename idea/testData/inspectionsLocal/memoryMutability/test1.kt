// PROBLEM: Trying mutate frozen object
// FIX: none
object Singleton {
    var i = 0
    fun increment() { i<caret>++ }
}
