package ru.restofleet.console.feature.incidents.ui.api

interface IIncidentsRouter {
    fun openIncident(incidentId: String)
}

interface IIncidentDetailsRouter {
    fun back()
}
