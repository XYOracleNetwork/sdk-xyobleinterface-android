package network.xyo.modbluetoothkotlin.client

import android.content.Context
import kotlinx.coroutines.*
import network.xyo.ble.devices.XY4BluetoothDevice
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.devices.XYCreator
import network.xyo.ble.gatt.peripheral.XYBluetoothError
import network.xyo.ble.gatt.peripheral.XYBluetoothResult
import network.xyo.ble.scanner.XYScanResult
import network.xyo.ble.services.standard.BatteryService
import network.xyo.ble.services.standard.DeviceInformationService
import network.xyo.ble.services.xy4.PrimaryService
import network.xyo.modbluetoothkotlin.XyoUuids
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.experimental.and

open class XyoSentinelX(context: Context, private val scanResult: XYScanResult, hash : Int) : XyoBluetoothClient(context, scanResult, hash) {
    private val sentinelListeners = HashMap<String, Listener>()
    private var lastButtonPressTime : Long = 0

    private val batteryService = BatteryService(this)
    private val primary = PrimaryService(this)

    //Keep as public
    val deviceInformationService = DeviceInformationService(this)

    fun addButtonListener (key : String, listener : Listener) {
        sentinelListeners[key] = listener
    }

    fun removeButtonListener (key: String) {
        sentinelListeners.remove(key)
    }

    fun isClaimed () : Boolean {
        val iBeaconData = scanResult.scanRecord?.getManufacturerSpecificData(0x4c) ?: return true

        if (iBeaconData.size == 23) {
            val flags = iBeaconData[21]
            return flags and 1.toByte() != 0.toByte()
        }

        return true
    }

    private fun isButtonPressed (scanResult: XYScanResult) : Boolean {
        val iBeaconData = scanResult.scanRecord?.getManufacturerSpecificData(0x4c) ?: return true

        if (iBeaconData.size == 23) {
            val flags = iBeaconData[21]
            return flags and 2.toByte() != 0.toByte()
        }

        return false
    }

    override fun onDetect(scanResult: XYScanResult?) {
        if (scanResult != null && isButtonPressed(scanResult) && lastButtonPressTime < System.currentTimeMillis() - 11_000) {
            // button of sentinel x is pressed
            lastButtonPressTime = System.currentTimeMillis()
            // TODO - added delay to allow listener attachment before calling it. onButtonPressed needs to be separate.
            CoroutineScope(Dispatchers.IO).launch {
                delay(500)
                for ((_, l) in sentinelListeners) {
                    l.onButtonPressed()
                }
            }


            return
        }

        return
    }

    /**
     * Changes the password on the remote device if the current password is correct.
     *
     * @param password The password of the device now.
     * @param newPassword The password to change on the remote device.
     * @return An XYBluetoothError if there was an issue writing the packet.
     */
    fun changePassword (password: ByteArray, newPassword: ByteArray) : Deferred<XYBluetoothError?> {
        val encoded = ByteBuffer.allocate(2 + password.size + newPassword.size)
                .put((password.size + 1).toByte())
                .put(password)
                .put((newPassword.size + 1).toByte())
                .put(newPassword)
                .array()

        return chunkSend(encoded, XyoUuids.XYO_PIN, XyoUuids.XYO_SERVICE, 1)
    }

    /**
     * Changes the bound witness data on the remote device
     *
     * @param boundWitnessData The data to include in tche remote devices bound witness.
     * @param password The password of the device to so it can write the boundWitnessData
     * @return An XYBluetoothError if there was an issue writing the packet.
     */
    fun changeBoundWitnessData (password: ByteArray, boundWitnessData: ByteArray) : Deferred<XYBluetoothError?> {
        val encoded = ByteBuffer.allocate(3 + password.size + boundWitnessData.size)
                .put((password.size + 1).toByte())
                .put(password)
                .putShort((boundWitnessData.size + 2).toShort())
                .put(boundWitnessData)
                .array()

        return chunkSend(encoded, XyoUuids.XYO_BW, XyoUuids.XYO_SERVICE, 4)
    }

    fun getBoundWitnessData () : Deferred<XYBluetoothResult<ByteArray>> {
        return findAndReadCharacteristicBytes(XyoUuids.XYO_SERVICE, XyoUuids.XYO_BW)
    }

    /**
     * Unlock the device.
     */
    fun lock() = connection {
        return@connection primary.lock.set(XY4BluetoothDevice.DefaultLockCode).await()
    }

    /**
     * Unlock the device
     */
    fun unlock() = connection {
        return@connection primary.unlock.set(XY4BluetoothDevice.DefaultLockCode).await()
    }

    fun stayAwake() = connection {
        return@connection primary.stayAwake.set(1).await()
    }

    fun fallAsleep() = connection {
        return@connection primary.stayAwake.set(0).await()
    }

    fun batteryLevel() = connection {
        return@connection batteryService.level.get().await()
    }

    companion object : XYCreator() {

        open class Listener {
            open fun onButtonPressed () {}
        }

        fun enable (enable : Boolean) {
            if (enable) {
                XyoBluetoothClient.xyoManufactorIdToCreator[0x01] = this
            } else {
                XyoBluetoothClient.xyoManufactorIdToCreator.remove(0x01)
            }
        }

        override fun getDevicesFromScanResult(context: Context, scanResult: XYScanResult, globalDevices: ConcurrentHashMap<String, XYBluetoothDevice>, foundDevices: HashMap<String, XYBluetoothDevice>) {
            val hash = scanResult.device?.address.hashCode()
            val createdDevice = XyoSentinelX(context, scanResult, hash)
            foundDevices[hash.toString()] = createdDevice
            globalDevices[hash.toString()] = createdDevice
        }
    }
}