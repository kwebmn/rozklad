package io.github.kwebmn.rozklad.fakes

import io.github.kwebmn.rozklad.data.remote.SiteCodec

fun fixtureBytes(name: String): ByteArray =
    requireNotNull(object {}.javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
        .use { it.readBytes() }

fun fixture(name: String): String = SiteCodec.decode(fixtureBytes(name))
