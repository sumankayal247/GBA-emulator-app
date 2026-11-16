package io.github.sumankayal247.gbaemulator.emulator

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.sumankayal247.gbaemulator.data.preferences.PreferencesRepository
import io.github.sumankayal247.gbaemulator.domain.model.ConnectedControllerInfo
import io.github.sumankayal247.gbaemulator.domain.model.ControllerProfile
import io.github.sumankayal247.gbaemulator.domain.model.VirtualButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Observes Android Bluetooth HID & USB gamepads via [InputManager.InputDeviceListener],
 * persists per-device button mappings, supports interactive remapping & live testing, and
 * clears held inputs immediately on hot-plug disconnect (Plan §5.D / §11.4 / ADR-0005).
 */
class ControllerManager(
    context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val onGamepadBitMaskChanged: (Int) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
    private val tracker = GamepadInputTracker()

    private var savedProfiles: Map<String, ControllerProfile> = emptyMap()
    private val activeDeviceDescriptors = HashMap<Int, String>()

    private val _controllers = MutableStateFlow<List<ConnectedControllerInfo>>(emptyList())
    val controllers: StateFlow<List<ConnectedControllerInfo>> = _controllers.asStateFlow()

    private val _hasConnectedController = MutableStateFlow(false)
    val hasConnectedController: StateFlow<Boolean> = _hasConnectedController.asStateFlow()

    private val _pressedButtons = MutableStateFlow<Set<VirtualButton>>(emptySet())
    val pressedButtons: StateFlow<Set<VirtualButton>> = _pressedButtons.asStateFlow()

    private val _capturingBindingFor = MutableStateFlow<Pair<String, VirtualButton>?>(null)
    val capturingBindingFor: StateFlow<Pair<String, VirtualButton>?> = _capturingBindingFor.asStateFlow()

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            refreshDevices()
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            // Immediately release any buttons held by the disconnected controller!
            val newMask = tracker.onDeviceDisconnected(deviceId)
            activeDeviceDescriptors.remove(deviceId)
            _pressedButtons.value = tracker.pressedButtons()
            onGamepadBitMaskChanged(newMask)
            refreshDevices()
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            refreshDevices()
        }
    }

    init {
        scope.launch {
            preferencesRepository.controllerProfilesFlow.collect { profiles ->
                savedProfiles = profiles
                refreshDevices()
            }
        }
        inputManager?.registerInputDeviceListener(deviceListener, Handler(Looper.getMainLooper()))
        refreshDevices()
    }

    fun refreshDevices() {
        val deviceIds = inputManager?.inputDeviceIds ?: IntArray(0)
        val connectedList = ArrayList<ConnectedControllerInfo>()
        val connectedDescriptors = HashSet<String>()
        activeDeviceDescriptors.clear()

        for (id in deviceIds) {
            val dev = inputManager?.getInputDevice(id) ?: continue
            if (!isGamepadDevice(dev)) continue
            val descriptor = dev.descriptor.ifBlank { "dev_${dev.vendorId}_${dev.productId}_${dev.name}" }
            activeDeviceDescriptors[id] = descriptor
            connectedDescriptors.add(descriptor)
            val existing = savedProfiles[descriptor]
            val profile = existing ?: ControllerProfile(
                descriptor = descriptor,
                displayName = dev.name.ifBlank { "External Controller" },
                vendorId = dev.vendorId,
                productId = dev.productId,
            )
            // Persist first-time recognized controllers automatically so they appear in saved list.
            if (existing == null) {
                scope.launch { preferencesRepository.upsertControllerProfile(profile) }
            }
            connectedList.add(
                ConnectedControllerInfo(
                    deviceId = id,
                    descriptor = descriptor,
                    displayName = dev.name.ifBlank { profile.displayName },
                    vendorId = dev.vendorId,
                    productId = dev.productId,
                    isConnected = true,
                    isUsb = dev.name.contains("usb", ignoreCase = true) || dev.vendorId != 0 && !dev.name.contains("bluetooth", ignoreCase = true),
                    profile = profile,
                ),
            )
        }

        // Append previously recognized offline controller profiles.
        savedProfiles.values
            .filterNot { it.descriptor in connectedDescriptors }
            .sortedBy { it.displayName.lowercase() }
            .forEach { profile ->
                connectedList.add(
                    ConnectedControllerInfo(
                        deviceId = -1,
                        descriptor = profile.descriptor,
                        displayName = profile.displayName,
                        vendorId = profile.vendorId,
                        productId = profile.productId,
                        isConnected = false,
                        isUsb = false,
                        profile = profile,
                    ),
                )
            }

        _controllers.value = connectedList
        _hasConnectedController.value = connectedDescriptors.isNotEmpty()
    }

    fun startCapturingBinding(descriptor: String, button: VirtualButton) {
        _capturingBindingFor.value = descriptor to button
    }

    fun cancelCapturingBinding() {
        _capturingBindingFor.value = null
    }

    fun updateBinding(descriptor: String, button: VirtualButton, keyCode: Int) {
        val current = savedProfiles[descriptor]
            ?: _controllers.value.firstOrNull { it.descriptor == descriptor }?.profile
            ?: return
        val updatedMap = current.buttonKeyCodes.toMutableMap()
        updatedMap[button] = keyCode
        val updated = current.copy(buttonKeyCodes = updatedMap)
        scope.launch { preferencesRepository.upsertControllerProfile(updated) }
        _capturingBindingFor.value = null
    }

    fun updateDeadzone(descriptor: String, deadzone: Float) {
        val current = savedProfiles[descriptor]
            ?: _controllers.value.firstOrNull { it.descriptor == descriptor }?.profile
            ?: return
        val updated = current.copy(analogDeadzone = deadzone.coerceIn(0.15f, 0.70f))
        scope.launch { preferencesRepository.upsertControllerProfile(updated) }
    }

    fun resetProfileToDefault(descriptor: String) {
        val current = savedProfiles[descriptor]
            ?: _controllers.value.firstOrNull { it.descriptor == descriptor }?.profile
            ?: return
        val reset = current.copy(
            analogDeadzone = 0.35f,
            buttonKeyCodes = ControllerProfile.defaultBindings(),
        )
        scope.launch { preferencesRepository.upsertControllerProfile(reset) }
    }

    fun removeSavedProfile(descriptor: String) {
        scope.launch { preferencesRepository.removeControllerProfile(descriptor) }
    }

    fun releaseAllInputs() {
        val mask = tracker.releaseAll()
        _pressedButtons.value = emptySet()
        onGamepadBitMaskChanged(mask)
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val dev = event.device ?: return false
        if (!isGamepadDevice(dev)) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        // If the Controllers screen is actively listening to remap a button, capture key down!
        val captureTarget = _capturingBindingFor.value
        if (captureTarget != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            updateBinding(captureTarget.first, captureTarget.second, event.keyCode)
            return true
        }

        val profile = resolveProfileForDevice(dev)
        val pressed = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }

        val handled = tracker.onKeyInput(
            deviceId = dev.id,
            keyCode = event.keyCode,
            pressed = pressed,
            repeatCount = event.repeatCount,
            profile = profile,
        )
        if (handled) {
            _pressedButtons.value = tracker.pressedButtons()
            onGamepadBitMaskChanged(tracker.currentBitMask())
        }
        return handled
    }

    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        val dev = event.device ?: return false
        if (!isGamepadDevice(dev)) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false

        val profile = resolveProfileForDevice(dev)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)
        val lTrig = maxOf(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE),
        )
        val rTrig = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS),
        )

        val newMask = tracker.onAxesInput(
            deviceId = dev.id,
            hatX = hatX,
            hatY = hatY,
            stickX = stickX,
            stickY = stickY,
            lTrigger = lTrig,
            rTrigger = rTrig,
            profile = profile,
        )
        _pressedButtons.value = tracker.pressedButtons()
        onGamepadBitMaskChanged(newMask)
        return true
    }

    private fun resolveProfileForDevice(dev: InputDevice): ControllerProfile {
        val desc = activeDeviceDescriptors[dev.id]
            ?: dev.descriptor.ifBlank { "dev_${dev.vendorId}_${dev.productId}_${dev.name}" }
        return savedProfiles[desc] ?: ControllerProfile(
            descriptor = desc,
            displayName = dev.name.ifBlank { "External Controller" },
            vendorId = dev.vendorId,
            productId = dev.productId,
        )
    }

    companion object {
        fun isGamepadDevice(device: InputDevice): Boolean {
            if (device.isVirtual) return false
            val sources = device.sources
            val isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            val isJoystick = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            return isGamepad || isJoystick
        }

        fun formatKeyCodeLabel(keyCode: Int): String =
            KeyEvent.keyCodeToString(keyCode)
                .removePrefix("KEYCODE_")
                .replace("BUTTON_", "")
                .replace("_", " ")
    }
}
