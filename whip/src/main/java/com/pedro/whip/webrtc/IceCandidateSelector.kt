package com.pedro.whip.webrtc

/**
 * trackcast: remote-candidate selection for the single-pair ICE flow in
 * WhipClient. Upstream takes the first non-loopback candidate, but servers
 * behind NAT/container networks (e.g. MediaMTX in k8s) list unreachable
 * private addresses first (pod IP 10.x), with the publicly routable address
 * (webrtcAdditionalHosts) later in the list — talking only to [0] sends all
 * STUN checks into a black hole, so the session never connects.
 */
internal fun String.isPublicUnicastIp(): Boolean {
  // IPv6: exclude loopback, unspecified, link-local and unique-local.
  if (contains(":")) {
    val lower = lowercase()
    return !(lower == "::1" || lower == "::" || lower.startsWith("fe80:") ||
      lower.startsWith("fc") || lower.startsWith("fd"))
  }
  val octets = split(".").map { it.toIntOrNull() ?: return false }
  if (octets.size != 4 || octets.any { it !in 0..255 }) return false
  if (octets[0] == 10) return false
  if (octets[0] == 172 && octets[1] in 16..31) return false
  if (octets[0] == 192 && octets[1] == 168) return false
  if (octets[0] == 127 || octets[0] == 0) return false
  if (octets[0] == 169 && octets[1] == 254) return false
  return true
}

/**
 * trackcast: prefer a publicly routable candidate; fall back to the first
 * non-loopback (preserves old behavior for LAN deployments), then to the
 * first candidate at all. Null when the list is empty.
 */
internal fun selectRemoteCandidate(candidates: List<Candidate>): Candidate? {
  if (candidates.isEmpty()) return null
  return candidates.firstOrNull { it.getRealHost().isPublicUnicastIp() }
    ?: candidates.firstOrNull { it.getRealHost() != "127.0.0.1" }
    ?: candidates.firstOrNull()
}
