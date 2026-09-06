package io.nightfish.potatoautoproxy

interface ProxySource {
    suspend fun getProxies(): List<Proxy>
}