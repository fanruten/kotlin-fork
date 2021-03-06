/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.symbols.impl

import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.CallableId

abstract class FirCallableSymbol<D : FirCallableDeclaration<D>> : AbstractFirBasedSymbol<D>() {
    abstract val callableId: CallableId

    open val overriddenSymbol: FirCallableSymbol<D>?
        get() = null
}
