package com.mako.support

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.mako.support.inject.MakoPythonTestLayout

/**
 * Every test prepares the Python helpers layout. The pro locator throws on the
 * first typeshed lookup and that failure poisons the test JVM, so a test that
 * never injects Python still has to run this before it highlights or completes.
 */
abstract class MakoPlatformTestCase : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        MakoPythonTestLayout.prepare()
    }
}
