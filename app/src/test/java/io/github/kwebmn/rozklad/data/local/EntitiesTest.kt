package io.github.kwebmn.rozklad.data.local

import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.THU
import io.github.kwebmn.rozklad.fakes.lesson
import org.junit.Assert.assertEquals
import org.junit.Test

class EntitiesTest {
    @Test
    fun `lesson survives round trip`() {
        val full = lesson(THU, 2, remote = true, room = null)
            .copy(stream = "потік А", links = listOf("https://a", "https://b"))
        assertEquals(full, full.toEntity(subscriptionId = 7).toDomain())
        val bare = lesson(THU, 1, teacher = null, room = null)
        assertEquals(bare, bare.toEntity(7).toDomain())
        assertEquals(7L, bare.toEntity(7).subscriptionId)
    }

    @Test
    fun `subscription survives round trip`() {
        val sub = Subscription(3, SubscriptionType.TEACHER, "Демчина", "66", isPrimary = true, lastSyncedAt = 42)
        assertEquals(sub, sub.toEntity().toDomain())
        val noId = Subscription(4, SubscriptionType.GROUP, "ІПЗс-24-1", null)
        assertEquals(noId, noId.toEntity().toDomain())
    }
}
