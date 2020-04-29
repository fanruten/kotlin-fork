## Описание

Нужно сделать несколько проверок, находящих мутацию `frozen` объекта.

## Что делаем

Реализуем наивный алгоритм.

Для простоты, мутации скрытые в кложуре/методе рассматривать не будем.

Будут поддержаны:
* предупреждения при мутации на object
* предупреждения при мутации свойств (включая вложенные свойства) у `frozen` объектов

Пример:

``` kotlin
object Singleton {
    var i = 0
    fun increment() { i++ } // Пишем, что есть проблема
    fun decrement() { i -= 1}  // Пишем, что есть проблема
}

fun test1() {
    Singleton.i = 3 // Пишем, что есть проблема
}
```

Пример:

``` kotlin
class Foo {
    var i = 0
}

class Container {
    var foo = Foo()
    var m = 1

    fun update() { m = 3 }
}

fun test2() {
    var c = Container()
    c.foo.freeze()

    c.m = 5
    c.foo.i = 5  // Пишем, что есть проблема
    c.foo.i += 5  // Пишем, что есть проблема
    c.foo = Foo()  // Пишем, что есть проблема
}

fun test3() {
    var c = Container()
    c.foo.freeze()

    c.foo = Foo() // Заменили `frozen` объект, поэтому все хорошо и проблем нет

    c.m = 5
    c.foo.i = 5
    c.foo.i += 5
}

fun test4() {
    var c = Container()
    c.freeze()

    c.update() // Проблема не обнаружена
}
```

## Алгоритм

Объект может быть `frozen` изначально или стать таким после вызова `freeze()`.

Это значит, что есть два случая проверки на `frozen`.
В первом, мы смотрим на определение объекта и например проверяем, что это cинглтон.
Во втором, проходимся по коду вверх от мутации и проверяем, нет ли там вызова freeze.

Наивный алгоритм:
* Находим мутацию
* Смотрим на описание типа. Если это `frozen` объект, то сразу показываем warning и идем к следующей мутации.
* Если не `frozen`, то идем вверх по коду и ищем вызов `freeze()` на объекте, где происходит мутация или на его контейнере.

Мутация и вызов `freeze()`, могут быть сокрыты методами или кложурами, что сложно для анализа.

Пример:

``` kotlin
class Foo {
   var a = 0
   fun increment() { a++ }
}
```

Теперь вызов `increment`, надо рассматривать как мутацию.

Аналогично может быть с `freeze()`.

Пример:

``` kotlin
var foo = Foo()
doSomething({
  foo.freeze()
})
foo.mutate()
```

В случае кложур, еще нужно проверять, как кложура используется внутри метода и в какой момент вызывается (если вызывается вообще).
Эти случаи рассматривать не будем.

## PSI

Особое место в PSI, занимает `KtSimpleNameReference`.
На объектах этого типа, мы можем вызвать метод `resolve()` и получить описание структуры, на которую ссылается переменная.

## Как делаем

Нам нужно найти мутацию. Так как мутация может быть в цепочке вызовов, надо выбрать подходящий способ ее представления.
Для этого идеально подходит массив `KtSimpleNameReference`.

Пример:

``` kotlin
c.foo.i = 5
```

В таком случае мутация будет описана массивом ссылок `[c, foo, i]`.

С таким способ представления мутации, доступен следующий алгоритм:
* Если первая ссылка массива, указывает на object или свойство object, то выводим предупреждение.
* Если нет, то от того места, где была мутация, идем вверх по коду до начала текущего скоупа.
* Если встречаем мутацию, которая является подмножеством текущей, то уменьшаем скоуп мутации до нее.
* Если встречаем вызов метода (`KtDotQualifiedExpression`), то проверяем на вызов `freeze()`. 
Если это он, то получаем массив ссылок на элементы для которых вызван `freeze()`.
* Если массив freeze является подмассиовм мутации и мутации есть дополнительные элементы, то было изменение `frozen` элемента и мы выводим предупреждение.  

Пример:

``` kotlin
c.foo.i.freeze()
с.foo = Foo()
c.foo.i = 5
```

* Допустим начинаем с `c.foo.i = 5`
* У нас будет массив ссылок `[c, foo, i]`
* Идем вверх и встречаем `с.foo = Foo()` с массивом ссылок `[c, foo]`
* Так как он подмассив для `[c, foo, i]`, то теперь рассматриваем `[c, foo]`   
* Идем вверх и встречаем `c.foo.i.freeze()` с массивом ссылок `[c, foo, i]`
* `[c, foo, i]`  является надмножеством изменений `[c, foo]`, это значит, что мутации `frozen` данных нет.

Пример:

``` kotlin
c.freeze()
с.foo = Foo()
c.foo.i = 5
```

* Допустим начинаем с `c.foo.i = 5`
* У нас будет массив ссылок `[c, foo, i]`
* Идем вверх и встречаем `с.foo = Foo()` с массивом ссылок `[c, foo]`
* Так как он подмассив для `[c, foo, i]`, то теперь рассматриваем `[c, foo]`   
* Идем вверх и встречаем `c.freeze()` с массивом ссылок `[c]`
* `[c]` является подмножеством изменений `[c, foo]`, это значит, что есть мутация `frozen` данных.


## Ищем мутацию

Мутация возможна, когда мы что-то присваиваем в переменную.

Пример:

``` kotlin
var a = 5
a = 3
a += 2
a -= 1
```

Важно учесть, что объекты могут быть вложены.

Пример:

``` kotlin
var c = Container()
c.foo.i = 5
c.foo.i += 5
```

В PSI, присваивание выражается через `KtBinaryExpression`, а инкремент/декремент, через `KtUnaryExpression`.
`KtBinaryExpression` и `KtUnaryExpression` являются наследниками `KtOperationExpression`.

Итого, нам нужно найти все ссылки на изменяемые значения (`KtReferenceExpression`), которые могут быть вложены в `KtDotQualifiedExpression` (вложенные вызовы).

Пример возможной реализации:

``` kotlin
private fun KtOperationExpression.mutatedReferences(): List<KtSimpleNameReference> {
    val stack = ArrayList<PsiElement>(0)
    stack.add(firstChild)

    val mutated = ArrayList<KtSimpleNameReference>(0)

    while (stack.isNotEmpty()) {
        val item = stack.first()
        stack.removeAt(0)

        when (item) {
            is KtDotQualifiedExpression ->
                stack.addAll(0, item.children.toList())

            is KtReferenceExpression -> {
                val nameRef = item.references.find { it is KtSimpleNameReference } as? KtSimpleNameReference
                if (nameRef != null) {
                    mutated.add(nameRef)
                }
            }
        }
    }

    return mutated
}
```

Пример:

``` kotlin
c.foo.i = 5
```

Метод должен вернуть три ссылки (`KtSimpleNameReference`) `[c, foo, i]`

## Проверка на `frozen` объект

Изначально `frozen` объектом являются синглтон (object).

Есть еще дополнительные аттрибуты, влияющие на заморозку объекта (`@konan.ThreadLocal`, `@konan.SharedImmutable`).
В рамках простой проверки, пропустим их.

Пример:

``` kotlin
object Singleton {
    var i = 0
    fun increment() { i++ }
    fun decrement() { i -= 1}
}

fun test() {
    Singleton.i = 3
}
```


Предположим, что у нас есть ссылка на изменяемую переменную (`KtSimpleNameReference`).
Проверить ее можно следующим кодом.


``` kotlin
private fun KtSimpleNameReference.isSingletonPropertyRef(): Boolean {
    when (val item = resolve()) {
        is KtObjectDeclaration ->
            return true

        is KtProperty -> {
            val classBody = item.parent as? KtClassBody ?: return false
            return classBody.parent is KtObjectDeclaration
        }
    }
    return false
}
```

### Ищем freeze()

В PSI, вызов функции выражается через `KtCallExpression`.
Для поиска `freeze()` просто проверим, что `KtCallExpression` указывает на функцию у которой свойство canonicalText равно "freeze".

Пример возможной реализации:

``` kotlin
private fun KtDotQualifiedExpression.freezeCallSubject(): ArrayList<KtSimpleNameReference> {
    val refs = ArrayList<KtSimpleNameReference>(0)

    val call = children.find { it is KtCallExpression } ?: return refs
    val invokeFunction = call.children.find { it is KtReferenceExpression } ?: return refs
    val nameReference = invokeFunction.references.find { it is KtSimpleNameReference } ?: return refs

    if (nameReference.canonicalText == "freeze") {
        val stack = ArrayList<PsiElement>(0)
        stack.add(firstChild)

        while (stack.isNotEmpty()) {
            val item = stack.first()
            stack.removeAt(0)

            when (item) {
                is KtDotQualifiedExpression ->
                    stack.addAll(0, item.children.toList())                    

                is KtReferenceExpression -> {
                    val nameRef = item.references.find { it is KtSimpleNameReference } as? KtSimpleNameReference
                    if (nameRef != null) {
                        refs.add(nameRef)
                    }
                }
            }
        }
    }

    return refs
}
```

Например, анализируем строку `c.foo.freeze()`.
Для нее `freezeCallSubject` вернет массив идентификаторов `[c, foo]`