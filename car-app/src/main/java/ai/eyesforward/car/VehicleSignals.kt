package ai.eyesforward.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Pleos Vehicle SDK로 2열 좌석 신호(벨트·착석·문)와 기어를 구독해 규칙 엔진에 넘긴다.
 * 기어는 Vehicle SDK를 먼저 시도하고, 안 되면 공식 문서 안내대로 AAOS CarPropertyManager(GEAR_SELECTION)를 쓴다.
 */
class VehicleSignals(context: Context, private val engine: RuleEngine, private val onStatus: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context.applicationContext)
    private val appContext = context.applicationContext
    private val listeners = mutableListOf<() -> Unit>() // 해제용
    private var car: Car? = null

    private val seatMap = mapOf("left" to SeatValue.ROW_2_LEFT, "right" to SeatValue.ROW_2_RIGHT)
    private val doorMap = mapOf("left" to DoorValue.ROW_2_LEFT, "right" to DoorValue.ROW_2_RIGHT)

    fun start() {
        try {
            vehicle.initialize()
        } catch (e: Exception) {
            onStatus("Vehicle SDK 초기화 실패: ${e.message}"); return
        }
        val seat = vehicle.getSeat()
        val door = vehicle.getDoor()

        seatMap.forEach { (id, value) ->
            val area = SeatArea(value)
            safe("벨트 $id") {
                val l = object : SeatBeltBuckledStateListener {
                    override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) {
                        if (buckled != null) main.post { engine.onBelt(id, buckled, "Vehicle SDK") }
                    }
                    override fun onFailed(e: Exception) { Log.w(TAG, "belt $id", e) }
                }
                seat.registerSeatBeltBuckledState(area, l)
                listeners += { seat.unregisterSeatBeltBuckledState(area, l) }
            }
            safe("착석 $id") {
                val l = object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(occupied: Boolean?) {
                        if (occupied != null) main.post { engine.onOccupancy(id, occupied, "Vehicle SDK") }
                    }
                    override fun onFailed(e: Exception) { Log.w(TAG, "occupancy $id", e) }
                }
                seat.registerSeatOccupancy(area, l)
                listeners += { seat.unregisterSeatOccupancy(area, l) }
            }
        }
        doorMap.forEach { (id, value) ->
            val area = DoorArea(value)
            safe("문 $id") {
                val l = object : DoorPositionListener {
                    override fun onPositionUpdated(pos: Int?) {
                        if (pos != null) main.post { engine.onDoor(id, pos > 0, "Vehicle SDK") }
                    }
                    override fun onFailed(e: Exception) { Log.w(TAG, "door $id", e) }
                }
                door.registerDoorPosition(area, l)
                listeners += { door.unregisterDoorPosition(area, l) }
            }
        }
        if (!startGearWithVehicleSdk()) startGearWithCarProperty()
        onStatus("Vehicle SDK 구독 중: 2열 벨트·착석·문, 기어")
    }

    private fun startGearWithVehicleSdk(): Boolean = try {
        val gear = vehicle.getGear()
        val l = object : GearSelectionListener {
            override fun onSelected(g: VehicleGear?) { main.post { handleGear(g == VehicleGear.GEAR_DRIVE, g == VehicleGear.GEAR_PARK, "Vehicle SDK") } }
            override fun onFailed(e: Exception) { Log.w(TAG, "gear sdk", e); main.post { startGearWithCarProperty() } }
        }
        gear.registerGearSelection(l)
        listeners += { gear.unregisterGearSelection(l) }
        true
    } catch (e: Throwable) {
        Log.w(TAG, "gear sdk unavailable", e); false
    }

    private var carPropertyStarted = false
    private fun startGearWithCarProperty() {
        if (carPropertyStarted) return
        carPropertyStarted = true
        try {
            val c = Car.createCar(appContext) ?: return
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            pm.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(v: CarPropertyValue<*>) {
                    val g = v.value as? Int ?: return
                    // VehicleGear: PARK=4, DRIVE=8
                    main.post { handleGear(g == 8, g == 4, "CarPropertyManager") }
                }
                override fun onErrorEvent(propId: Int, zone: Int) {}
            }, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        } catch (e: Throwable) {
            Log.w(TAG, "gear car property", e)
        }
    }

    private fun handleGear(drive: Boolean, park: Boolean, source: String) {
        if (drive) engine.onGear(RuleEngine.Gear.DRIVE, source)
        else if (park) engine.onGear(RuleEngine.Gear.PARK, source)
    }

    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { Log.w(TAG, "register $what", e); onStatus("$what 구독 실패: ${e.message}") }
    }

    fun stop() {
        listeners.forEach { runCatching(it) }
        listeners.clear()
        runCatching { vehicle.release() }
        runCatching { car?.disconnect() }
    }

    companion object { const val TAG = "EyesForward" }
}
