package net.devslash

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

annotation class DSLLockedValue

/**
 * Lockable value is a delegate pattern that allows for a marker annotation to be used to lock.
 *
 * Doesn't work on primitives. If you want it to work on a primitive, then wrap it in a data class for now
 */
class LockableValue<R, T>(private var curValue: T) : ReadWriteProperty<R, T> {
  private val locked = AtomicBoolean(false)

  override fun getValue(thisRef: R, property: KProperty<*>): T {
    return curValue
  }

  override fun setValue(thisRef: R, property: KProperty<*>, value: T) {

    val annotation = if (value != null) value!!::class.java.getAnnotation(DSLLockedValue::class.java) else null
    if (annotation != null) {
      // Then we have someone who wants to be protected. Therefore lock it down
      // Unless it's already set, in which complain
      if (locked.get() && curValue != null) {
        throw AlreadySetException(property, curValue)
      }
      if (locked.compareAndSet(false, true)) {
        curValue = value
        return
      } else {
        throw AlreadySetException(property, curValue)
      }
    }

    // If we've already locked. Also throw
    if (locked.get()) {
      throw AlreadySetException(property, curValue)
    }

    // Otherwise we can set
    curValue = value
  }

  fun lock() {
    if (!locked.compareAndSet(false, true)) {
      throw AlreadySetException2()
    }
  }

  class AlreadySetException2 :
    RuntimeException()

  class AlreadySetException(kProperty: KProperty<*>, value: Any?) :
    RuntimeException("Property \"${kProperty.name}\" has already been set to ${value}. Cannot be set again")
}
