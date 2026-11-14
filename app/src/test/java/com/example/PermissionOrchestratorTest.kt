package com.example

import com.example.permissions.PermissionKind
import com.example.permissions.PermissionOrchestrator
import com.example.permissions.PermissionRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PermissionOrchestratorTest {

    @Before
    fun setUp() {
        PermissionOrchestrator.resetAllSuppression()
    }

    @Test
    fun permissionRegistry_hasExactly26Specs() {
        val specs = PermissionRegistry.getAll()
        assertEquals("Permission registry must have exactly 26 specs", 26, specs.size)
    }

    @Test
    fun permissionRegistry_allSpecsHaveValidBengaliMetadata() {
        val specs = PermissionRegistry.getAll()
        for (spec in specs) {
            assertTrue("Spec ${spec.id} must have a non-blank titleBn", spec.titleBn.isNotBlank())
            assertTrue("Spec ${spec.id} must have a non-blank whyBn", spec.whyBn.isNotBlank())
            assertTrue("Spec ${spec.id} must have a non-blank whatItEnablesBn", spec.whatItEnablesBn.isNotBlank())
            assertTrue("Spec ${spec.id} must have a non-blank privacyNoteBn", spec.privacyNoteBn.isNotBlank())
        }
    }

    @Test
    fun permissionRegistry_resolvesSpecsForSensitiveTools() {
        val accessibilitySpecs = PermissionRegistry.getForTool("performSystemAction")
        assertTrue(accessibilitySpecs.any { it.id == "accessibility" })

        val clickScreenSpecs = PermissionRegistry.getForTool("clickScreen")
        assertTrue(clickScreenSpecs.any { it.id == "accessibility" })

        val gpsSpecs = PermissionRegistry.getForTool("getCurrentGpsLocation")
        assertTrue(gpsSpecs.any { it.id == "location_fine" })

        val callSpecs = PermissionRegistry.getForTool("makePhoneCall")
        assertTrue(callSpecs.any { it.id == "call_phone" })

        val screenSpecs = PermissionRegistry.getForTool("captureAndAnalyzeScreen")
        assertTrue(screenSpecs.any { it.id == "screen_capture" })
    }

    @Test
    fun permissionOrchestrator_handlesPostponeAndDismiss() {
        val cameraSpec = PermissionRegistry.get("camera")
        assertNotNull(cameraSpec)

        PermissionOrchestrator.request(cameraSpec!!)
        assertNotNull(PermissionOrchestrator.currentRequest.value)

        PermissionOrchestrator.markPostponed("camera")
        assertNull(PermissionOrchestrator.currentRequest.value)
        assertTrue(PermissionOrchestrator.isPostponed("camera"))

        PermissionOrchestrator.clearPostponed("camera")
        assertFalse(PermissionOrchestrator.isPostponed("camera"))
    }

    @Test
    fun permissionOrchestrator_handlesPermanentDenial() {
        val micSpec = PermissionRegistry.get("microphone")
        assertNotNull(micSpec)

        PermissionOrchestrator.markPermanentlyDenied("microphone")
        assertTrue(PermissionOrchestrator.isPermanentlyDenied("microphone"))

        PermissionOrchestrator.resetAllSuppression()
        assertFalse(PermissionOrchestrator.isPermanentlyDenied("microphone"))
    }
}
