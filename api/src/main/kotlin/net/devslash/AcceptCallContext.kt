package net.devslash

interface AcceptCallContext<T> {
  fun inject(): CallBuilder<T>.() -> Unit
}
