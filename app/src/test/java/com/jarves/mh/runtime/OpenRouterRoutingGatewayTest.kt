package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenRouterRoutingGatewayTest {
    @Test
    fun routingPolicyIsAddedWithoutChangingTheRequest() {
        val source = JSONObject()
            .put("model", "anthropic/claude-sonnet-4.6")
            .put("stream", true)
        val profile = ProviderProfile(
            kind = ProviderKind.LLM_ROUTER,
            openRouterProviderOrder = "anthropic, amazon-bedrock, anthropic",
            openRouterAllowFallbacks = false,
        )

        val routed = applyOpenRouterRouting(source, profile)

        assertEquals("anthropic/claude-sonnet-4.6", routed.getString("model"))
        assertEquals(true, routed.getBoolean("stream"))
        val policy = routed.getJSONObject("provider")
        assertEquals(listOf("anthropic", "amazon-bedrock"), policy.getJSONArray("order").let { array ->
            (0 until array.length()).map(array::getString)
        })
        assertFalse(policy.getBoolean("allow_fallbacks"))
    }

    @Test
    fun blankProviderOrderLeavesAutomaticRoutingUntouched() {
        val source = JSONObject().put("model", "auto")

        applyOpenRouterRouting(source, ProviderProfile(ProviderKind.LLM_ROUTER))

        assertFalse(source.has("provider"))
    }
}
