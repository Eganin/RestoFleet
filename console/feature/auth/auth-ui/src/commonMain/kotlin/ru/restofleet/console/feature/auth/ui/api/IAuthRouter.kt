package ru.restofleet.console.feature.auth.ui.api

/** Навигация наружу: фича не знает, куда идти после входа. */
interface IAuthRouter {
    fun onLoggedIn()
}
