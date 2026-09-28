package io.github.hagbard235.ringnotes.phone

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.github.hagbard235.ringnotes.pushToTalk

/**
 * Optional accessibility service: lets "hold volume-down to talk" work outside
 * the app while the screen is on. It only filters key events; it reads no
 * screen content.
 */
class VolumeKeyService : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean = pushToTalk.onKeyEvent(event)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
