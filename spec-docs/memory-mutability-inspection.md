## Описание

Нужно сделать несколько проверок, находящих мутацию `frozen` объекта.

## Вводные

Замороженным (`frozen`) объектом, является синглтон (`object`) и любой другой объект, на котором вызван метод `freeze`.
Изменение полей `frozen` объекта приводит к выбросу исключения.

Объект мутирует при изменении его полей, которое возможно только при использовании оператора присваивания.
А вызов оператора присваивания возможен только из тела метода.

Выходит, что надо просмотреть тело кажого метода, найти присваивания в поля `object` или объекта на котором был вызван `frozen`.
И показать предупреждение.

## Что делаем

Чтобы мутировать или заморозить объект, нам нужна ссылка на него. 

Получить ее можно через:
* глобальную переменную
* свойство объекта
* локальную переменную
* аргумент метода
* результат вызова метода

Для простоты, будем рассматривать только ссылки через свойства и локальные/глобальные переменные.

Таким образом, будут поддержаны:
* мутации на object
* мутации свойств (включая вложенные свойства) у `frozen` объектов через присваивание 
* мутации свойств (включая вложенные свойства) у `frozen` объектов через вызов метода


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

    fun update(): Int { 
        m = 3
        return m
    }
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

    c.update() // Пишем, что есть проблема
    val n = c.update() // Пишем, что есть проблема
}
```

## PSI

Анализ выполняется на представлении программы в виде дерева PSI элементов.

Среди PSI элементов интересны следующие.

`KtCallExpression` — описывает вызов метода.

`KtDotQualifiedExpression` — описывает вызов обращение через точку `.`. Это может быть вызов метода или доступ к свойству.

`KtOperationExpression` — описывает операцию присваивания.

`KtReferenceExpression` — описывает использование ссылки (например идентификатора). 

Cвойство `KtReferenceExpression.references`, содержит используемые выражением ссылки.
Сслыка может указывать на переменную, метод, свойство, ...

Если в поле ```KtReferenceExpression.references()``` есть объект типа `KtSimpleNameReference`, то можно получить элемент на который ссылается `KtReferenceExpression`.
Для этого нужно вызвать `KtSimpleNameReference.resolve()`.

## Алгоритм

Наивный алгоритм:
* Находим описанием метода и начинаем анализировать его тело.
* Если встречаем вызов `freeze`, то запоминаем ссылку на объект, на котором он вызван (добавляем в список `frozens`).
Если `freeze` вызвается на `this` или метод принадлежит `object`, то отмечаем, что текущий объект (`this`) теперь `frozen`.
* Если встречаем замену ссылки на `frozen` объекта, то убираем ссылку из списка `forzen`.
* Если встречаем мутацию свойства объекта, ссылка на который есть в списке `forzen`, или мутацию `this` и мы уже отметили, что `this` заморожен, то выводим предупреждение.
* Если встречаем мутацию объекта, на который нет ссылки в списке `forzen` и которая происходит через свойство объекта, то добавляем ссылку в список `mutations`.
* Если встречаем вызов метода, то запускаем на нем такой-же алгоритм и запрашиваем список мутированных/замороженных свойств объекта, чтобы дополнить списки `mutations`/`frozens`.
Дополнительно узнаем мутирует ли метод текущий объект (`this`) и если мутирует, а текущий объект `frozen`, то выводим предупреждение.
* В конце анализа метода, сохраняем информацию о том, какие ссылки были заморожены и мутированы.

## Как делаем

Нужен какой-то способ, чтобы описать ссылки на объекты, на которых происходит мутация/заморозка.
При этом надо учесть, что может быть цепочка ссылок.

Пример:

``` kotlin
c.foo.i = 5
```

Для этого идеально подходит массив `KtSimpleNameReference`.

Напишем функцию для получения такой цепочки:
``` kotlin
private fun PsiElement.callReferences(): List<KtSimpleNameReference> {
    val refs = ArrayList<KtSimpleNameReference>(0)

    val stack = ArrayList<PsiElement>(0)
    stack.add(this)

    while (stack.isNotEmpty()) {
        val item = stack.first()
        stack.removeAt(0)

        when (item) {
            is KtDotQualifiedExpression ->
                stack.addAll(0, item.children.toList())

            is KtCallExpression -> {
                item.calleeExpression?.let {
                    stack.add(0, it)
                }
            }

            is KtReferenceExpression -> {
                val nameRef = item.references.find { it is KtSimpleNameReference } as? KtSimpleNameReference
                if (nameRef != null) {
                    refs.add(nameRef)
                } else {
                    break
                }
            }
        }
    }

    return refs
}
```

Иногда `KtSimpleNameReference` нельзя получить, например когда код не дописан или есть ошибка. 
Поэтому прервем построение цепочки, если `KtSimpleNameReference` нет. 

Пример:

``` kotlin
c.foo.i.freeze()
```

Получим цепочку ```['c', 'foo', 'i', 'freeze']```

Если вызвать ```resolve()``` на элементах этого массива, то мы получим конкретные PSI элементы, ```KtProperty```,  ```KtNamedFunction```, ...

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

## Проверка на `frozen` объект

Изначально `frozen` объектом являются синглтон (```object```).

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


Предположим, что у нас есть ссылка (`KtSimpleNameReference`).
Проверить, что это сслыка на свойство `object`, можно следующим кодом.


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
private fun PsiElement.isFreezeCall(): Boolean {
    val props = callReferences().mapNotNull { it.resolve() }

    if (props.dropLast(1).isItemsOf(clazz = KtProperty::class.java) &&
        (props.last() as? KtNamedFunction)?.name == "freeze"
    ) {
        return true
    }

    return false
}
```
