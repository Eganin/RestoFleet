package ru.restofleet.agent.data.probes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.InetAddress

class GatewayResolverTest {
    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    @Test
    fun noRoutesGiveNull() {
        assertThat(GatewayResolver.pick(emptyList()) as Any?).isNull()
    }

    @Test
    fun nonDefaultRoutesAndRoutesWithoutGatewayAreIgnored() {
        val routes = listOf(
            RouteCandidate(isDefault = false, gateway = ip("10.0.0.9")),
            RouteCandidate(isDefault = true, gateway = null),
        )
        assertThat(GatewayResolver.pick(routes) as Any?).isNull()
    }

    @Test
    fun onLinkAnyAddressIsNotAGateway() {
        assertThat(GatewayResolver.pick(listOf(RouteCandidate(true, ip("0.0.0.0")))) as Any?).isNull()
    }

    @Test
    fun defaultRouteGatewayIsReturned() {
        val routes = listOf(RouteCandidate(false, ip("10.0.0.9")), RouteCandidate(true, ip("192.168.1.1")))
        assertThat(GatewayResolver.pick(routes)).isEqualTo(ip("192.168.1.1"))
    }

    @Test
    fun ipv4IsPreferredOverIpv6() {
        val routes = listOf(RouteCandidate(true, ip("fe80::1")), RouteCandidate(true, ip("192.168.1.1")))
        assertThat(GatewayResolver.pick(routes)).isEqualTo(ip("192.168.1.1"))
    }

    @Test
    fun ipv6IsUsedWhenNoIpv4() {
        assertThat(GatewayResolver.pick(listOf(RouteCandidate(true, ip("fe80::1"))))).isEqualTo(ip("fe80::1"))
    }
}
