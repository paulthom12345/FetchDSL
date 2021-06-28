package net.devslash

import org.hamcrest.CoreMatchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

internal class LockableValueTest {

  // What i can do is have `lock` as a
  @DSLLockedValue
  data class Locked(val b: Boolean)
  data class Unlocked(val b: Boolean)

  @Test
  fun testLockWillAcceptNullable() {
    val x: Int? by LockableValue(null)
    assertThat(x, `is`(nullValue()))
  }

  @Test
  fun testLockSetOnAnnotated() {
    var x: Locked? by LockableValue(null)
    x = Locked(true)
    assertThrows(LockableValue.AlreadySetException::class.java) {
      x = Locked(false)
    }
    assertThat(x, equalTo(Locked(true)))
  }

  @Test
  fun testNotLockedTillFirstAnnotatedValue() {
    var x: Any? by LockableValue(null)
    x = Unlocked(false)
    x = Locked(true)
    assertThrows(LockableValue.AlreadySetException::class.java) {
      x = Unlocked(true)
    }
    assertThat(x, equalTo(Locked(true)))
  }

  @Test
  fun testLockDoesNotSetOnUnannotated() {
    var x: Unlocked? by LockableValue(null)
    x = Unlocked(true)
    x = Unlocked(false)
    assertThat(x, equalTo(Unlocked(false)))

    x = null
    assertThat(x, `is`(nullValue()))
  }
}