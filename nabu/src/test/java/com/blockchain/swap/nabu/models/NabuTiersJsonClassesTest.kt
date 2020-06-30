package com.blockchain.swap.nabu.models

import com.blockchain.swap.nabu.models.nabu.LimitsJson
import com.blockchain.swap.nabu.models.nabu.TierResponse
import com.blockchain.swap.nabu.models.nabu.KycTiers
import com.blockchain.serialization.JsonSerializable
import com.blockchain.testutils.`should be assignable from`
import org.junit.Test

class NabuTiersJsonClassesTest {

    @Test
    fun `ensure TiersJson is JsonSerializable for proguard`() {
        JsonSerializable::class `should be assignable from` KycTiers::class
    }

    @Test
    fun `ensure TierJson is JsonSerializable for proguard`() {
        JsonSerializable::class `should be assignable from` TierResponse::class
    }

    @Test
    fun `ensure LimitsJson is JsonSerializable for proguard`() {
        JsonSerializable::class `should be assignable from` LimitsJson::class
    }
}
