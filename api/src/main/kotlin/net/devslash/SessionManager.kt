package net.devslash

interface SessionManager {
  fun <T> call(call: Call<T>, jar: CookieJar)
  fun <T> call(call: Call<T>)
}
