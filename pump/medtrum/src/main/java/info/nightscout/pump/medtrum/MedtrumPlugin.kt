package info.nightscout.pump.medtrum

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.text.format.DateFormat
import dagger.android.HasAndroidInjector
import info.nightscout.core.ui.toast.ToastUtils
import info.nightscout.core.utils.fabric.FabricPrivacy
import info.nightscout.interfaces.constraints.Constraint
import info.nightscout.interfaces.constraints.Constraints
import info.nightscout.interfaces.notifications.Notification
import info.nightscout.interfaces.plugin.PluginDescription
import info.nightscout.interfaces.plugin.PluginType
import info.nightscout.interfaces.profile.Profile
import info.nightscout.interfaces.profile.ProfileFunction
import info.nightscout.interfaces.pump.DetailedBolusInfo
import info.nightscout.interfaces.pump.DetailedBolusInfoStorage
import info.nightscout.interfaces.pump.Medtrum
import info.nightscout.interfaces.pump.Pump
import info.nightscout.interfaces.pump.PumpEnactResult
import info.nightscout.interfaces.pump.PumpPluginBase
import info.nightscout.interfaces.pump.PumpSync
import info.nightscout.interfaces.pump.TemporaryBasalStorage
import info.nightscout.interfaces.pump.actions.CustomAction
import info.nightscout.interfaces.pump.actions.CustomActionType
import info.nightscout.interfaces.pump.defs.ManufacturerType
import info.nightscout.interfaces.pump.defs.PumpDescription
import info.nightscout.interfaces.pump.defs.PumpType
import info.nightscout.interfaces.queue.Callback
import info.nightscout.interfaces.queue.CommandQueue
import info.nightscout.interfaces.queue.CustomCommand
import info.nightscout.interfaces.ui.UiInteraction
import info.nightscout.interfaces.utils.DecimalFormatter
import info.nightscout.interfaces.utils.TimeChangeType
import info.nightscout.pump.medtrum.comm.enums.MedtrumPumpState
import info.nightscout.pump.medtrum.ui.MedtrumOverviewFragment
import info.nightscout.pump.medtrum.services.MedtrumService
import info.nightscout.rx.AapsSchedulers
import info.nightscout.rx.bus.RxBus
import info.nightscout.rx.events.EventAppExit
import info.nightscout.rx.events.EventAppInitialized
import info.nightscout.rx.events.EventDismissNotification
import info.nightscout.rx.events.EventOverviewBolusProgress
import info.nightscout.rx.events.EventPreferenceChange
import info.nightscout.rx.logging.AAPSLogger
import info.nightscout.rx.logging.LTag
import info.nightscout.shared.interfaces.ResourceHelper
import info.nightscout.shared.sharedPreferences.SP
import info.nightscout.shared.utils.DateUtil
import info.nightscout.shared.utils.T
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.round

@Singleton class MedtrumPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    commandQueue: CommandQueue,
    private val constraintChecker: Constraints,
    private val sp: SP,
    private val aapsSchedulers: AapsSchedulers,
    private val rxBus: RxBus,
    private val context: Context,
    private val fabricPrivacy: FabricPrivacy,
    private val dateUtil: DateUtil,
    private val medtrumPump: MedtrumPump,
    private val uiInteraction: UiInteraction,
    private val profileFunction: ProfileFunction,
    private val pumpSync: PumpSync,
    private val temporaryBasalStorage: TemporaryBasalStorage
) : PumpPluginBase(
    PluginDescription()
        .mainType(PluginType.PUMP)
        .fragmentClass(MedtrumOverviewFragment::class.java.name)
        .pluginIcon(info.nightscout.core.ui.R.drawable.ic_generic_icon) // TODO
        .pluginName(R.string.medtrum)
        .shortName(R.string.medtrum_pump_shortname)
        .preferencesId(R.xml.pref_medtrum_pump)
        .description(R.string.medtrum_pump_description), injector, aapsLogger, rh, commandQueue
), Pump, Medtrum {

    private val disposable = CompositeDisposable()
    private var medtrumService: MedtrumService? = null

    override fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.PUMP, "MedtrumPlugin onStart()")
        val intent = Intent(context, MedtrumService::class.java)
        context.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
        disposable += rxBus
            .toObservable(EventAppExit::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ context.unbindService(mConnection) }, fabricPrivacy::logException)
    }

    override fun onStop() {
        aapsLogger.debug(LTag.PUMP, "MedtrumPlugin onStop()")
        context.unbindService(mConnection)
        disposable.clear()
        super.onStop()
    }

    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {
            aapsLogger.debug(LTag.PUMP, "Service is disconnected")
            medtrumService = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            aapsLogger.debug(LTag.PUMP, "Service is connected")
            val mLocalBinder = service as MedtrumService.LocalBinder
            medtrumService = mLocalBinder.serviceInstance
        }
    }

    fun getService(): MedtrumService? {
        return medtrumService
    }

    override fun isInitialized(): Boolean {
        return medtrumPump.pumpState > MedtrumPumpState.EJECTED && medtrumPump.pumpState < MedtrumPumpState.STOPPED
    }

    override fun isSuspended(): Boolean {
        return medtrumPump.pumpState < MedtrumPumpState.ACTIVE || medtrumPump.pumpState > MedtrumPumpState.ACTIVE_ALT
    }

    override fun isBusy(): Boolean {
        return false
    }

    override fun isConnected(): Boolean {
        // This is a workaround to prevent AAPS to trigger connects when we have no patch activated
        return if (!isInitialized()) true else medtrumService?.isConnected ?: false
    }

    override fun isConnecting(): Boolean = medtrumService?.isConnecting ?: false
    override fun isHandshakeInProgress(): Boolean = false

    override fun finishHandshaking() {
    }

    override fun connect(reason: String) {
        if (isInitialized()) {
            aapsLogger.debug(LTag.PUMP, "Medtrum connect - reason:$reason")
            if (medtrumService != null) {
                aapsLogger.debug(LTag.PUMP, "Medtrum connect - Attempt connection!")
                val success = medtrumService?.connect(reason) ?: false
                if (!success) ToastUtils.errorToast(context, info.nightscout.core.ui.R.string.ble_not_supported_or_not_paired)
            }
        }
    }

    override fun disconnect(reason: String) {
        if (isInitialized()) {
            aapsLogger.debug(LTag.PUMP, "Medtrum disconnect from: $reason")
            medtrumService?.disconnect(reason)
        }
    }

    override fun stopConnecting() {
        if (isInitialized()) {
            aapsLogger.debug(LTag.PUMP, "Medtrum stopConnecting")
            medtrumService?.stopConnecting()
        }
    }

    override fun getPumpStatus(reason: String) {
        aapsLogger.debug(LTag.PUMP, "Medtrum getPumpStatus - reason:$reason")
        if (isInitialized()) {
            val connectionOK = medtrumService?.readPumpStatus() ?: false
            if (connectionOK == false) {
                aapsLogger.error(LTag.PUMP, "Medtrum getPumpStatus failed")
            }
        }
    }

    override fun setNewBasalProfile(profile: Profile): PumpEnactResult {
        // New profile will be set when patch is activated
        if (!isInitialized()) return PumpEnactResult(injector).success(true).enacted(true)

        return if (medtrumService?.updateBasalsInPump(profile) == true) {
            rxBus.send(EventDismissNotification(Notification.FAILED_UPDATE_PROFILE))
            uiInteraction.addNotificationValidFor(Notification.PROFILE_SET_OK, rh.gs(info.nightscout.core.ui.R.string.profile_set_ok), Notification.INFO, 60)
            PumpEnactResult(injector).success(true).enacted(true)
        } else {
            uiInteraction.addNotification(Notification.FAILED_UPDATE_PROFILE, rh.gs(info.nightscout.core.ui.R.string.failed_update_basal_profile), Notification.URGENT)
            PumpEnactResult(injector)
        }
    }

    override fun isThisProfileSet(profile: Profile): Boolean {
        if (!isInitialized()) return true
        var result = false
        val profileBytes = medtrumPump.buildMedtrumProfileArray(profile)
        if (profileBytes?.size == medtrumPump.actualBasalProfile.size) {
            result = true
            for (i in profileBytes.indices) {
                if (profileBytes[i] != medtrumPump.actualBasalProfile[i]) {
                    result = false
                    break
                }
            }
        }
        return result
    }

    override fun lastDataTime(): Long = medtrumPump.lastConnection
    override val baseBasalRate: Double
        get() = medtrumPump.baseBasalRate

    override val reservoirLevel: Double
        get() = medtrumPump.reservoir

    override val batteryLevel: Int
        get() = 0 // We cannot determine battery level (yet)

    @Synchronized
    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        aapsLogger.debug(LTag.PUMP, "deliverTreatment: " + detailedBolusInfo.insulin + "U")
        if (!isInitialized()) return PumpEnactResult(injector).success(false).enacted(false)
        detailedBolusInfo.insulin = constraintChecker.applyBolusConstraints(Constraint(detailedBolusInfo.insulin)).value()
        return if (detailedBolusInfo.insulin > 0 && detailedBolusInfo.carbs == 0.0) {
            aapsLogger.debug(LTag.PUMP, "deliverTreatment: Delivering bolus: " + detailedBolusInfo.insulin + "U")
            val t = EventOverviewBolusProgress.Treatment(0.0, 0, detailedBolusInfo.bolusType == DetailedBolusInfo.BolusType.SMB, detailedBolusInfo.id)
            val connectionOK = medtrumService?.setBolus(detailedBolusInfo, t) ?: false
            val result = PumpEnactResult(injector)
            result.success = connectionOK && abs(detailedBolusInfo.insulin - t.insulin) < pumpDescription.bolusStep
            result.bolusDelivered = t.insulin
            if (!result.success) {
                // Note: There are no error codes
                result.comment = "failed"
            } else {
                result.comment = "ok"
            }
            aapsLogger.debug(LTag.PUMP, "deliverTreatment: OK. Success: ${result.success} Asked: ${detailedBolusInfo.insulin} Delivered: ${result.bolusDelivered}")
            result
        } else {
            aapsLogger.debug(LTag.PUMP, "deliverTreatment: Invalid input")
            val result = PumpEnactResult(injector)
            result.success = false
            result.bolusDelivered = 0.0
            result.comment = rh.gs(info.nightscout.core.ui.R.string.invalid_input)
            aapsLogger.error("deliverTreatment: Invalid input")
            result
        }
    }

    override fun stopBolusDelivering() {
        if (!isInitialized()) return

        aapsLogger.info(LTag.PUMP, "stopBolusDelivering")
        medtrumService?.stopBolus()
    }

    @Synchronized
    override fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        if (!isInitialized()) return PumpEnactResult(injector).success(false).enacted(false)

        aapsLogger.info(LTag.PUMP, "setTempBasalAbsolute - absoluteRate: $absoluteRate, durationInMinutes: $durationInMinutes, enforceNew: $enforceNew")
        // round rate to pump rate
        val pumpRate = constraintChecker.applyBasalConstraints(Constraint(absoluteRate), profile).value()
        temporaryBasalStorage.add(PumpSync.PumpState.TemporaryBasal(dateUtil.now(), T.mins(durationInMinutes.toLong()).msecs(), pumpRate, true, tbrType, 0L, 0L))
        val connectionOK = medtrumService?.setTempBasal(pumpRate, durationInMinutes) ?: false
        if (connectionOK
            && medtrumPump.tempBasalInProgress
            && Math.abs(medtrumPump.tempBasalAbsoluteRate - pumpRate) <= 0.05
        ) {

            return PumpEnactResult(injector).success(true).enacted(true).duration(durationInMinutes).absolute(medtrumPump.tempBasalAbsoluteRate)
                .isPercent(false)
                .isTempCancel(false)
        } else {
            aapsLogger.error(
                LTag.PUMP,
                "setTempBasalAbsolute failed, connectionOK: $connectionOK, tempBasalInProgress: ${medtrumPump.tempBasalInProgress}, tempBasalAbsoluteRate: ${medtrumPump.tempBasalAbsoluteRate}"
            )
            return PumpEnactResult(injector).success(false).enacted(false).comment("Medtrum setTempBasalAbsolute failed")
        }
    }

    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        aapsLogger.info(LTag.PUMP, "setTempBasalPercent - percent: $percent, durationInMinutes: $durationInMinutes, enforceNew: $enforceNew")
        return PumpEnactResult(injector).success(false).enacted(false).comment("Medtrum driver does not support percentage temp basals")
    }

    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult {
        aapsLogger.info(LTag.PUMP, "setExtendedBolus - insulin: $insulin, durationInMinutes: $durationInMinutes")
        return PumpEnactResult(injector).success(false).enacted(false).comment("Medtrum driver does not support extended boluses")
    }

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        if (!isInitialized()) return PumpEnactResult(injector).success(false).enacted(false)

        aapsLogger.info(LTag.PUMP, "cancelTempBasal - enforceNew: $enforceNew")
        val connectionOK = medtrumService?.cancelTempBasal() ?: false
        if (connectionOK && !medtrumPump.tempBasalInProgress) {
            return PumpEnactResult(injector).success(true).enacted(true).isTempCancel(true)
        } else {
            aapsLogger.error(LTag.PUMP, "cancelTempBasal failed, connectionOK: $connectionOK, tempBasalInProgress: ${medtrumPump.tempBasalInProgress}")
            return PumpEnactResult(injector).success(false).enacted(false).comment("Medtrum cancelTempBasal failed")
        }
    }

    override fun cancelExtendedBolus(): PumpEnactResult {
        return PumpEnactResult(injector)
    }

    override fun getJSONStatus(profile: Profile, profileName: String, version: String): JSONObject {
        val now = System.currentTimeMillis()
        if (medtrumPump.lastConnection + 60 * 60 * 1000L < System.currentTimeMillis()) {
            return JSONObject()
        }
        val pumpJson = JSONObject()
        val status = JSONObject()
        val extended = JSONObject()
        try {
            status.put(
                "status", if (!isSuspended()) "normal"
                else if (isInitialized() && isSuspended()) "suspended"
                else "no active patch"
            )
            status.put("timestamp", dateUtil.toISOString(medtrumPump.lastConnection))
            if (medtrumPump.lastBolusTime != 0L) {
                extended.put("lastBolus", dateUtil.dateAndTimeString(medtrumPump.lastBolusTime))
                extended.put("lastBolusAmount", medtrumPump.lastBolusAmount)
            }
            val tb = pumpSync.expectedPumpState().temporaryBasal
            if (tb != null) {
                extended.put("TempBasalAbsoluteRate", tb.convertedToAbsolute(now, profile))
                extended.put("TempBasalStart", dateUtil.dateAndTimeString(tb.timestamp))
                extended.put("TempBasalRemaining", tb.plannedRemainingMinutes)
            }
            extended.put("BaseBasalRate", baseBasalRate)
            try {
                extended.put("ActiveProfile", profileName)
            } catch (ignored: Exception) {
            }
            pumpJson.put("status", status)
            pumpJson.put("extended", extended)
            pumpJson.put("reservoir", medtrumPump.reservoir.toInt())
            pumpJson.put("clock", dateUtil.toISOString(now))
        } catch (e: JSONException) {
            aapsLogger.error(LTag.PUMP, "Unhandled exception: $e")
        }
        return pumpJson
    }

    override fun manufacturer(): ManufacturerType {
        return ManufacturerType.Medtrum
    }

    override fun model(): PumpType {
        return medtrumPump.pumpType()
    }

    override fun serialNumber(): String {
        return medtrumPump.pumpSN.toString(radix = 16)
    }

    override val pumpDescription: PumpDescription
        get() = PumpDescription(medtrumPump.pumpType())

    override fun shortStatus(veryShort: Boolean): String {
        var ret = ""
        if (medtrumPump.lastConnection != 0L) {
            val agoMillis = System.currentTimeMillis() - medtrumPump.lastConnection
            val agoMin = (agoMillis / 60.0 / 1000.0).toInt()
            ret += "LastConn: $agoMin minAgo\n"
        }
        if (medtrumPump.lastBolusTime != 0L)
            ret += "LastBolus: ${DecimalFormatter.to2Decimal(medtrumPump.lastBolusAmount)}U @${DateFormat.format("HH:mm", medtrumPump.lastBolusTime)}\n"

        if (medtrumPump.tempBasalInProgress)
            ret += "Temp: ${medtrumPump.temporaryBasalToString()}\n"

        ret += "Res: ${DecimalFormatter.to0Decimal(medtrumPump.reservoir)}U\n"
        return ret
    }

    override val isFakingTempsByExtendedBoluses: Boolean = false

    override fun loadTDDs(): PumpEnactResult {
        return PumpEnactResult(injector) // Note: Can implement this if we implement history fully (no priority)
    }

    override fun getCustomActions(): List<CustomAction>? {
        return null
    }

    override fun executeCustomAction(customActionType: CustomActionType) {
    }

    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult? {
        return null
    }

    override fun canHandleDST(): Boolean {
        return true
    }

    override fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
        medtrumPump.needTimeUpdate = true
        if (isInitialized()) {
            commandQueue.updateTime(object : Callback() {
                override fun run() {
                    medtrumService?.timeUpdateNotification(this.result.success)
                }
            })
        }
    }

    // Medtrum interface
    override fun loadEvents(): PumpEnactResult {
        if (!isInitialized()) {
            val result = PumpEnactResult(injector).success(false)
            result.comment = "pump not initialized"
            return result
        }
        val connectionOK = medtrumService?.loadEvents() ?: false
        return PumpEnactResult(injector).success(connectionOK)
    }

    override fun setUserOptions(): PumpEnactResult {
        if (!isInitialized()) {
            val result = PumpEnactResult(injector).success(false)
            result.comment = "pump not initialized"
            return result
        }
        val connectionOK = medtrumService?.setUserSettings() ?: false
        return PumpEnactResult(injector).success(connectionOK)
    }

    override fun clearAlarms(): PumpEnactResult {
        if (!isInitialized()) {
            val result = PumpEnactResult(injector).success(false)
            result.comment = "pump not initialized"
            return result
        }
        val connectionOK = medtrumService?.clearAlarms() ?: false
        return PumpEnactResult(injector).success(connectionOK)
    }

    override fun deactivate(): PumpEnactResult {
        val connectionOK = medtrumService?.deactivatePatch() ?: false
        return PumpEnactResult(injector).success(connectionOK)
    }

    override fun updateTime(): PumpEnactResult {
        if (!isInitialized()) {
            val result = PumpEnactResult(injector).success(false)
            result.comment = "pump not initialized"
            return result
        }
        val connectionOK = medtrumService?.updateTime() ?: false
        return PumpEnactResult(injector).success(connectionOK)
    }
}
