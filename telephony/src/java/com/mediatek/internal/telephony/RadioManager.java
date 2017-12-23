/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein is
 * confidential and proprietary to MediaTek Inc. and/or its licensors. Without
 * the prior written permission of MediaTek inc. and/or its licensors, any
 * reproduction, modification, use or disclosure of MediaTek Software, and
 * information contained herein, in whole or in part, shall be strictly
 * prohibited.
 *
 * MediaTek Inc. (C) 2014. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER
 * ON AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL
 * WARRANTIES, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR
 * NONINFRINGEMENT. NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH
 * RESPECT TO THE SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY,
 * INCORPORATED IN, OR SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES
 * TO LOOK ONLY TO SUCH THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO.
 * RECEIVER EXPRESSLY ACKNOWLEDGES THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO
 * OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES CONTAINED IN MEDIATEK
 * SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK SOFTWARE
 * RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S
 * ENTIRE AND CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE
 * RELEASED HEREUNDER WILL BE, AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE
 * MEDIATEK SOFTWARE AT ISSUE, OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE
 * CHARGE PAID BY RECEIVER TO MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek
 * Software") have been modified by MediaTek Inc. All revisions are subject to
 * any receiver's applicable license agreements with MediaTek Inc.
 */

package com.mediatek.internal.telephony;

import android.os.Handler;
import android.os.Message;
import android.os.AsyncResult;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.Registrant;
import android.os.RegistrantList;
import android.provider.Settings;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.Rlog;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SubscriptionController;
import com.mediatek.internal.telephony.cdma.CdmaFeatureOptionUtils;
import com.mediatek.internal.telephony.IRadioPower;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map.Entry;

import com.mediatek.internal.telephony.ltedc.LteDcPhoneProxy;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteRatController;
import com.mediatek.internal.telephony.uicc.SvlteUiccUtils;

public class RadioManager extends Handler  {

    static final String LOG_TAG = "RadioManager";
    private static final String PREF_CATEGORY_RADIO_STATUS = "RADIO_STATUS";
    private static RadioManager sRadioManager;

    private static final int MODE_PHONE1_ONLY = 1;
    private static final int MODE_PHONE2_ONLY = 2;
    private static final int MODE_PHONE3_ONLY = 4;
    private static final int MODE_PHONE4_ONLY = 8;

    private static final int INVALID_PHONE_ID = -1;

    private static final int SIM_NOT_INITIALIZED = -1;
    private static final int NO_SIM_INSERTED = 0;
    private static final int SIM_INSERTED = 1;

    private static final boolean ICC_READ_NOT_READY = false;
    private static final boolean ICC_READ_READY = true;

    private static final int INITIAL_RETRY_INTERVAL_MSEC = 200;

    private static final boolean RADIO_POWER_OFF = false;
    private static final boolean RADIO_POWER_ON = true;

    private static final boolean MODEM_POWER_OFF = false;
    private static final boolean MODEM_POWER_ON = true;

    private static final boolean AIRPLANE_MODE_OFF = false;
    private static final boolean AIRPLANE_MODE_ON = true;
    private boolean mAirplaneMode = AIRPLANE_MODE_OFF;

    private static final String STRING_NO_SIM_INSERTED = "N/A";

    private static final String PROPERTY_SILENT_REBOOT_MD1 = "gsm.ril.eboot";
    private static final String PROPERTY_SILENT_REBOOT_MD2 = "gsm.ril.eboot.2";
    private static final String IS_NOT_SILENT_REBOOT = "0";
    private static final String IS_SILENT_REBOOT = "1";
    private static final String NO_NAME = "NO_NAME";

    public static final String ACTION_FORCE_SET_RADIO_POWER =
        "com.mediatek.internal.telephony.RadioManager.intent.action.FORCE_SET_RADIO_POWER";

    private int[] mSimInsertedStatus;
    private Context mContext;
    private int[] mInitializeWaitCounter;
    private CommandsInterface[] mCi;
    private static SharedPreferences mIccidPreference;
    private int mPhoneCount;
    private int mBitmapForPhoneCount;
    private boolean[] mModemPower;

    private ImsSwitchController mImsSwitchController = null;

    static protected ConcurrentHashMap<IRadioPower, String> mNotifyRadioPowerChange
            = new ConcurrentHashMap<IRadioPower, String>();

    private String[] PROPERTY_ICCID_SIM = {
        "ril.iccid.sim1",
        "ril.iccid.sim2",
        "ril.iccid.sim3",
        "ril.iccid.sim4",
    };

    /** events id definition */
    private static final int EVENT_VIRTUAL_SIM_ON = 1;

    public static RadioManager init(Context context, int phoneCount, CommandsInterface[] ci) {
        synchronized (RadioManager.class) {
            if (sRadioManager == null) {
                sRadioManager = new RadioManager(context, phoneCount, ci);
            }
            return sRadioManager;
        }
    }

    /**
     * @internal
     */
    public static RadioManager getInstance() {
        return sRadioManager;
    }

    private RadioManager(Context context , int phoneCount, CommandsInterface[] ci) {

        int airplaneMode = Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0);
        log("Initialize RadioManager under airplane mode:" + airplaneMode);

        mSimInsertedStatus = new int[phoneCount];
        for (int i = 0; i < phoneCount; i++) {
            mSimInsertedStatus[i] = SIM_NOT_INITIALIZED;
        }
        mInitializeWaitCounter = new int[phoneCount];
        for (int i = 0; i < phoneCount; i++) {
            mInitializeWaitCounter[i] = 0;
        }

        mContext = context;
        mAirplaneMode = ((airplaneMode == 0) ? AIRPLANE_MODE_OFF : AIRPLANE_MODE_ON);
        mCi = ci;
        mPhoneCount = phoneCount;
        mBitmapForPhoneCount = convertPhoneCountIntoBitmap(phoneCount);
        mIccidPreference = mContext.getSharedPreferences(PREF_CATEGORY_RADIO_STATUS, 0);
        mModemPower = new boolean[mPhoneCount];

        mImsSwitchController = new ImsSwitchController(mContext, mPhoneCount, mCi);

        for (int i = 0; i < phoneCount; i++) {
            mModemPower[i] = MODEM_POWER_ON;
        }

        if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
            log("Not BSP Package, register intent!!!");
            IntentFilter filter = new IntentFilter();
            filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            filter.addAction(ACTION_FORCE_SET_RADIO_POWER);
            mContext.registerReceiver(mIntentReceiver, filter);

            // For virtual SIM
            for (int i = 0; i < phoneCount; i++) {
                Integer index = new Integer(i);
                mCi[i].registerForVirtualSimOn(this, EVENT_VIRTUAL_SIM_ON, index);
            }

        }
    }

    private int convertPhoneCountIntoBitmap(int phoneCount) {
        int ret = 0;
        for (int i = 0; i < phoneCount; i++) {
            ret += MODE_PHONE1_ONLY << i;
        }
        log("Convert phoneCount " + phoneCount + " into bitmap " + ret);
        return ret;
    }

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

           log("BroadcastReceiver: " + intent.getAction());

            if (intent.getAction().equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                onReceiveSimStateChangedIntent(intent);
            } else if (intent.getAction().equals(ACTION_FORCE_SET_RADIO_POWER)) {
                onReceiveForceSetRadioPowerIntent(intent);
            }
        }
    };

    private void onReceiveSimStateChangedIntent(Intent intent) {
        String simStatus = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);

        // TODO: phone_key now is equals to slot_key, change in the future
        int phoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY, INVALID_PHONE_ID);

        if (!isValidPhoneId(phoneId)) {
            log("INTENT:Invalid phone id:" + phoneId + ", do nothing!");
            return;
        }

        log("INTENT:SIM_STATE_CHANGED: " + intent.getAction() + ", sim status: " + simStatus + ", phoneId: " + phoneId );
        boolean desiredRadioPower = RADIO_POWER_ON;

        if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(simStatus)
            || IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(simStatus)
            || IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(simStatus)) {
            mSimInsertedStatus[phoneId] = SIM_INSERTED;
            log("Phone[" + phoneId + "]: " + simStatusToString(SIM_INSERTED));

            // if we receive ready, but can't get iccid, we do nothing
            String iccid = readIccIdUsingPhoneId(phoneId);
            if (STRING_NO_SIM_INSERTED.equals(iccid)) {
                log("Phone " + phoneId + ":SIM ready but ICCID not ready, do nothing");
                return;
            }

            desiredRadioPower = RADIO_POWER_ON;
            if (mAirplaneMode == AIRPLANE_MODE_OFF) {
                log("Set Radio Power due to SIM_STATE_CHANGED, power: " + desiredRadioPower + ", phoneId: " + phoneId);
                setRadioPower(desiredRadioPower, phoneId);
            }
        }

        else if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(simStatus)) {
            mSimInsertedStatus[phoneId] = NO_SIM_INSERTED;
            log("Phone[" + phoneId + "]: " + simStatusToString(NO_SIM_INSERTED));
            desiredRadioPower = RADIO_POWER_OFF;
            if (mAirplaneMode == AIRPLANE_MODE_OFF) {
                log("Set Radio Power due to SIM_STATE_CHANGED, power: " + desiredRadioPower + ", phoneId: " + phoneId);
                setRadioPower(desiredRadioPower, phoneId);
            }
        }
    }

    private void onReceiveForceSetRadioPowerIntent(Intent intent) {
        int phoneId = 0;
        int mode = intent.getIntExtra(Intent.EXTRA_MSIM_MODE, -1);
        log("force set radio power, mode: " + mode);
        if (mode == -1) {
            log("Invalid mode, MSIM_MODE intent has no extra value");
            return;
        }
        for (phoneId = 0; phoneId < mPhoneCount; phoneId++) {
            boolean singlePhonePower =
                ((mode & (MODE_PHONE1_ONLY << phoneId)) == 0) ? RADIO_POWER_OFF : RADIO_POWER_ON;
            if (RADIO_POWER_ON == singlePhonePower) {
                forceSetRadioPower(true, phoneId);
            }
        }
    }

    private boolean isValidPhoneId(int phoneId) {
        if (phoneId < 0 || phoneId >= TelephonyManager.getDefault().getPhoneCount()) {
            return false;
        } else {
            return true;
        }
    }

    private String simStatusToString(int simStatus) {
        String result = null;
        switch(simStatus) {
            case SIM_NOT_INITIALIZED:
                result = "SIM HAVE NOT INITIALIZED";
                break;
            case SIM_INSERTED:
                result = "SIM DETECTED";
                break;
            case NO_SIM_INSERTED:
                result = "NO SIM DETECTED";
                break;
            }
        return result;
    }

    /**
     * Modify mAirplaneMode and set modem power
     * @param enabled 0: normal mode
     *                1: airplane mode
     * @internal
     */
    public void notifyAirplaneModeChange(boolean enabled) {

        // we expect airplane mode is on-> off or off->on
        if (enabled == mAirplaneMode) {
            log("enabled = " + enabled + ", mAirplaneMode = "+ mAirplaneMode + "is not expected (the same)");
            return;
        }

        mAirplaneMode = enabled;
        log("Airplane mode changed:" + enabled);

        if (isFlightModePowerOffModemEnabled() && !isUnderCryptKeeper()) {
            log("Airplane mode changed: turn on/off all modem");
            boolean modemPower = enabled ? MODEM_POWER_OFF : MODEM_POWER_ON;
            setSilentRebootPropertyForAllModem(IS_SILENT_REBOOT);
            setModemPower(modemPower, mBitmapForPhoneCount);
        } else if (isMSimModeSupport()) {
            log("Airplane mode changed: turn on/off all radio");
            boolean radioPower = enabled ? RADIO_POWER_OFF : RADIO_POWER_ON;
            for (int i = 0; i < mPhoneCount; i++) {
                setRadioPower(radioPower, i);
            }
            if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                setRadioPower(radioPower, SubscriptionManager.LTE_DC_PHONE_ID);
            }
        }
    }

    /*
     *A special paragraph, not to trun off modem power under cryptkeeper
     */
    private boolean isUnderCryptKeeper() {
        if (SystemProperties.get("ro.crypto.state").equals("encrypted")
            && SystemProperties.get("vold.decrypt").equals("trigger_restart_min_framework")) {
            log("[Special Case] Under CryptKeeper, Not to turn on/off modem");
            return true;
        }
        log("[Special Case] Not Under CryptKeeper");
        return false;
    }

    private void setSilentRebootPropertyForAllModem(String isSilentReboot) {
        TelephonyManager.MultiSimVariants config = TelephonyManager.getDefault().getMultiSimConfiguration();
        switch(config) {
            case DSDS:
                log("set eboot under DSDS");
                SystemProperties.set(PROPERTY_SILENT_REBOOT_MD1, isSilentReboot);
                break;
            case DSDA:
                log("set eboot under DSDA");
                SystemProperties.set(PROPERTY_SILENT_REBOOT_MD1, isSilentReboot);
                SystemProperties.set(PROPERTY_SILENT_REBOOT_MD2, isSilentReboot);
                break;
            case TSTS:
                log("set eboot under TSTS");
                SystemProperties.set(PROPERTY_SILENT_REBOOT_MD1, isSilentReboot);
                break;
            default:
                log("set eboot under SS");
                SystemProperties.set(PROPERTY_SILENT_REBOOT_MD1, isSilentReboot);
                break;
        }
    }

    /*
     * Called From GSMSST, if boot up under airplane mode, power-off modem
     */
    public void notifyRadioAvailable(int phoneId) {
        log("Phone " + phoneId + " notifies radio available");
        if (mAirplaneMode == AIRPLANE_MODE_ON && isFlightModePowerOffModemEnabled() && !isUnderCryptKeeper()) {
            log("Power off modem because boot up under airplane mode");
            setModemPower(MODEM_POWER_OFF, phoneId);
        }
    }

    public void notifyIpoShutDown() {
        log("IPO shutdown!");
        setModemPower(MODEM_POWER_OFF, mBitmapForPhoneCount);
    }

    public void notifyIpoPreBoot() {
        log("IPO preboot!");
        setSilentRebootPropertyForAllModem(IS_NOT_SILENT_REBOOT);
        setModemPower(MODEM_POWER_ON, mBitmapForPhoneCount);
    }

    /**
     * Set modem power on/off according to DSDS or DSDA.
     *
     * @param power desired power of modem
     * @param phoneId a bit map of phones you want to set
     *              1: phone 1 only
     *              2: phone 2 only
     *              3: phone 1 and phone 2
     */
    public void setModemPower(boolean power, int phoneBitMap) {
        log("Set Modem Power according to bitmap, Power:" + power + ", PhoneBitMap:" + phoneBitMap);
        TelephonyManager.MultiSimVariants config = TelephonyManager.getDefault().getMultiSimConfiguration();

        int phoneId = 0;
        switch(config) {
            case DSDS:
                phoneId = findMainCapabilityPhoneId();
                log("Set Modem Power under DSDS mode, Power:" + power + ", phoneId:" + phoneId);
                /// M: c2k modify. @{
                if (PhoneFactory.getPhone(phoneId).getPhoneType() ==
                        PhoneConstants.PHONE_TYPE_CDMA) {
                    mCi[phoneId].setModemPower(power, null);
                } else { /// @}
                    for (int i = 0; i < mPhoneCount; i++) {
                        mModemPower[i] = power;
                    }
                    mCi[phoneId].setModemPower(power, null);
                }
                if (power == MODEM_POWER_OFF) {
                    for (int i = 0; i < mPhoneCount; i++) {
                        resetSimInsertedStatus(i);
                    }
                }
                break;

            case DSDA:
                for (int i = 0; i < mPhoneCount; i++) {
                    phoneId = i;
                    if ((phoneBitMap & (MODE_PHONE1_ONLY << i)) != 0) {
                        log("Set Modem Power under DSDA mode, Power:" + power + ", phoneId:" + phoneId);
                        /// M: c2k modify. @{
                        if (PhoneFactory.getPhone(phoneId).getPhoneType() ==
                                PhoneConstants.PHONE_TYPE_CDMA) {
                            mCi[phoneId].setModemPower(power, null);
                        } else { /// @}
                            mModemPower[phoneId] = power;
                            mCi[phoneId].setModemPower(power, null);
                        }
                        if (power == MODEM_POWER_OFF) {
                            resetSimInsertedStatus(phoneId);
                        }
                    }
                }
                break;

            case TSTS:
                phoneId = findMainCapabilityPhoneId();
                log("Set Modem Power under TSTS mode, Power:" + power + ", phoneId:" + phoneId);
                for (int i = 0; i < mPhoneCount; i++) {
                    mModemPower[i] = power;
                }
                mCi[phoneId].setModemPower(power, null);
                if (power == MODEM_POWER_OFF) {
                    for (int i = 0; i < mPhoneCount; i++) {
                        resetSimInsertedStatus(i);
                    }
                }
                break;

            default:
                phoneId = PhoneFactory.getDefaultPhone().getPhoneId();
                log("Set Modem Power under SS mode:" + power + ", phoneId:" + phoneId);
                mModemPower[phoneId] = power;
                mCi[phoneId].setModemPower(power, null);
                break;
        }
    }

    private int findMainCapabilityPhoneId() {
        int result = 0;
        int switchStatus = Integer.valueOf(
                SystemProperties.get(PhoneConstants.PROPERTY_CAPABILITY_SWITCH, "1"));
        result = switchStatus - 1;
        if (result < 0 || result >= mPhoneCount) {
            return 0;
        } else {
            return result;
        }
    }

    private class RadioPowerRunnable implements Runnable {
        boolean retryPower;
        int retryPhoneId;
        public  RadioPowerRunnable(boolean power, int phoneId) {
            retryPower = power;
            retryPhoneId = phoneId;
        }
        @Override
        public void run() {
            setRadioPower(retryPower, retryPhoneId);
        }
    }

    /*
     * MTK flow to control radio power
     */
    public void setRadioPower(boolean power, int phoneId) {

        if (isFlightModePowerOffModemEnabled() && mAirplaneMode == AIRPLANE_MODE_ON) {
            log("Set Radio Power under airplane mode, ignore");
            return;
        }

        // TODO: workaround, remove after
        int id = phoneId;
        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()
                && phoneId == SubscriptionManager.LTE_DC_PHONE_ID) {
            id = 0;
        }
        if (isModemPowerOff(id)) {
            log("modem for phone " + phoneId + " off, do not set radio again");
            return;
        }

        /**
        * We want iccid ready berfore we check if SIM is once manually turned-offedd
        * So we check ICCID repeatedly every 300 ms
        */
        if (!isIccIdReady(phoneId)) {
            log("RILD initialize not completed, wait for " + INITIAL_RETRY_INTERVAL_MSEC + "ms");
            RadioPowerRunnable setRadioPowerRunnable = new RadioPowerRunnable(power, phoneId);
            postDelayed(setRadioPowerRunnable, INITIAL_RETRY_INTERVAL_MSEC);
            return;
        }

        setSimInsertedStatus(phoneId);

        boolean radioPower = power;
        String iccId = readIccIdUsingPhoneId(phoneId);
        //adjust radio power according to ICCID
        if (mIccidPreference.contains(iccId)) {
            log("Adjust radio to off because once manually turned off, iccid: " + iccId + " , phone: " + phoneId);
            radioPower = RADIO_POWER_OFF;
        }

        boolean isCTACase = checkForCTACase();

        /// M: [SVLTE] handle the RAT mode when radio power. Assume SVLTE in slot 0.@{
        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
            SvlteRatController.SvlteRatMode ratMode = getSvlteRatMode(mContext);
            log("RadioManager before set radio, phoneId=" + phoneId + ", ratMode=" + ratMode);
            if (power && (phoneId == SubscriptionManager.LTE_DC_PHONE_ID || phoneId == 0)) {
                LteDcPhoneProxy lteDcPhoneProxy = (LteDcPhoneProxy) PhoneFactory.getPhone(0);
                int phoneType = lteDcPhoneProxy.getPhoneById(phoneId).getPhoneType();
                if (ratMode == SvlteRatController.SvlteRatMode.SVLTE_RAT_MODE_4G_DATA_ONLY &&
                        phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    log("setRadioPower ignore power on C2K in 4G data only mode");
                    return;
                } else if (ratMode == SvlteRatController.SvlteRatMode.SVLTE_RAT_MODE_3G &&
                        phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    log("setRadioPower ignore power on LTE in 3G data only mode");
                    return;
                }
            }
        }
        /// @}

        if (getSimInsertedStatus(phoneId) == NO_SIM_INSERTED) {
            if (isCTACase == true) {
                int capabilityPhoneId = findMainCapabilityPhoneId();
                log("No SIM inserted, force to turn on 3G/4G phone " + capabilityPhoneId + " radio if no any sim radio is enabled!");
                PhoneFactory.getPhone(capabilityPhoneId).setRadioPower(RADIO_POWER_ON);
            } else {
                log("No SIM inserted, turn Radio off!");
                radioPower = RADIO_POWER_OFF;
                PhoneFactory.getPhone(phoneId).setRadioPower(radioPower);
            }
        } else {
            log("Trigger set Radio Power, power: " + radioPower + ", phoneId: " + phoneId);
            // We must refresh sim setting during boot up or if we adjust power according to ICCID
            refreshSimSetting(radioPower, phoneId);
            if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                updatePhoneRadioPower(radioPower, phoneId);
            } else {
                PhoneFactory.getPhone(phoneId).setRadioPower(radioPower);
            }
        }
    }

    // For C2K SVLTE
    private static SvlteRatController.SvlteRatMode getSvlteRatMode(Context context) {
        int ratMode =  Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.LTE_ON_CDMA_RAT_MODE,
                SvlteRatController.SvlteRatMode.SVLTE_RAT_MODE_4G.ordinal());
        return SvlteRatController.SvlteRatMode.values()[ratMode];
    }

    // For C2K SVLTE @{
    private int getSimInsertedStatus(int phoneId) {
        if (phoneId == SubscriptionManager.LTE_DC_PHONE_ID) {
            phoneId = 0;
        }
        return mSimInsertedStatus[phoneId];
    }

    private void updatePhoneRadioPower(boolean power, int phoneId) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        // FIXME: To find a better way to get SVLTE card/phone slot
        final int svlteSlot = 0;
        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport() &&
                (phoneId == svlteSlot || phoneId == SubscriptionManager.LTE_DC_PHONE_ID)) {
            if (phoneId == SubscriptionManager.LTE_DC_PHONE_ID && power) {
                // LTE: need enable radio when UICC is 4G CT
                if (SvlteUiccUtils.getInstance().isUsimWithCsim(svlteSlot)) {
                    phone.setRadioPower(power);
                } else {
                    log("updatePhoneRadioPower: slot0 not CT LTE card, no need turn on radio!");
                }
            } else if (phoneId == svlteSlot && power) {
                // C2K: need enable radio when UICC is CDMA card
                if (SvlteUiccUtils.getInstance().isRuimCsim(svlteSlot)) {
                    phone.setRadioPower(power);
                } else {
                    log("updatePhoneRadioPower: slot0 not CDMA card, no need turn on radio!");
                }
            } else {
                phone.setRadioPower(power);
            }
        } else {
            phone.setRadioPower(power);
        }
    }

    private void updateMsimModeRadioPower(boolean power, int phoneId) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        // FIXME: To figure out a better way to get SVLTE phone slot
        if (0 == phoneId) {
            // Power PS channel if needed
            if (SvlteUiccUtils.getInstance().isUsimWithCsim(phoneId)) {
                log("updateMsimModeRadioPower: PS channel power: " + power);
                PhoneFactory.getPhone(SubscriptionManager.LTE_DC_PHONE_ID).setRadioPower(power);
            }
            // Power CS channel
            phone.setRadioPower(power);
        } else {
            phone.setRadioPower(power);
        }
    }
    // @}

    private void setSimInsertedStatus(int phoneId) {
        String iccId = readIccIdUsingPhoneId(phoneId);
        // For C2K SVLTE, if using lte phone id, change to 0
        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()
                && phoneId == SubscriptionManager.LTE_DC_PHONE_ID) {
            phoneId = 0;
        }
        if (STRING_NO_SIM_INSERTED.equals(iccId)) {
            mSimInsertedStatus[phoneId] = NO_SIM_INSERTED;
        } else {
            mSimInsertedStatus[phoneId] = SIM_INSERTED;
        }
    }

    private boolean isIccIdReady(int phoneId) {
        String iccId = readIccIdUsingPhoneId(phoneId);
        boolean ret = ICC_READ_NOT_READY;
        if (iccId == null || "".equals(iccId)) {
            log("ICC read not ready for phone:" + phoneId);
            ret = ICC_READ_NOT_READY;
        } else {
            log("ICC read ready, iccid[" + phoneId + "]: " + iccId);
            ret = ICC_READ_READY;
        }
        return ret;
    }

    private String readIccIdUsingPhoneId(int phoneId) {
        String ret;
        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport() &&
                (phoneId == 0 || phoneId == SubscriptionManager.LTE_DC_PHONE_ID)) {
            String svlteLte = SystemProperties.get("ril.iccid.sim1_lte");
            String svlteC2k = SystemProperties.get("ril.iccid.sim1_c2k");
            String cdmaType = SystemProperties.get("gsm.ril.uicc.3gpp2type");
            log("readIccIdUsingPhoneId phoneId=" + phoneId + ", svlet_lte="
                    + svlteLte + ", svlet_c2k=" + svlteC2k + ", cdmaType=" + cdmaType);
            if ((svlteLte == null || svlteLte.equals(""))
                    || (svlteC2k == null || svlteC2k.equals(""))) {
                ret = null;
            } else {
                ret = svlteC2k;
                SystemProperties.set(PROPERTY_ICCID_SIM[0], svlteC2k);
            }
            if (ret == null && svlteLte != null && !svlteLte.equals("") && "2".equals(cdmaType)) {
                log("non CDMA card, update ICCID use LTE iccid only");
                ret = svlteLte;
                SystemProperties.set(PROPERTY_ICCID_SIM[0], svlteLte);
            }
        } else {
            ret = SystemProperties.get(PROPERTY_ICCID_SIM[phoneId]);
        }
        log("ICCID for phone " + phoneId + " is " + ret);
        return ret;
    }

    private boolean checkForCTACase() {
        boolean isCTACase = true;
        log("Check For CTA case!");
        if (mAirplaneMode == AIRPLANE_MODE_OFF) {
            for (int i = 0; i < mPhoneCount; i++) {
                log("Check For CTA case: mSimInsertedStatus[" + i + "]:"  + mSimInsertedStatus[i]);
                if (mSimInsertedStatus[i] == SIM_INSERTED || mSimInsertedStatus[i] == SIM_NOT_INITIALIZED) {
                    isCTACase = false;
                }
            }
        } else {
            isCTACase = false;
        }

        if (isCTACase == false) {
            turnOffCTARadioIfNecessary();
        }
        log("CTA case: " + isCTACase);
        return isCTACase;
    }

    /*
     * We need to turn off Phone's radio if no SIM inserted (radio on because CTA) after we leave CTA case
     */
    private void turnOffCTARadioIfNecessary() {
        for (int i = 0; i < mPhoneCount; i++) {
            if (mSimInsertedStatus[i] == NO_SIM_INSERTED) {
                if (isModemPowerOff(i)) {
                    log("modem off, not to handle CTA");
                    return;
                } else {
                    log("turn off phone " + i + " radio because we are no longer in CTA mode");
                    PhoneFactory.getPhone(i).setRadioPower(RADIO_POWER_OFF);
                }
            }
        }
    }

    /*
     * Refresh MSIM Settings only when:
     * We auto turn off a SIM card once manually turned off
     */
    private void refreshSimSetting(boolean radioPower, int phoneId) {

        int simMode = Settings.System.getInt(mContext.getContentResolver(),
                          Settings.System.MSIM_MODE_SETTING, mBitmapForPhoneCount);
        int oldMode = simMode;

        if (radioPower == RADIO_POWER_OFF) {
            simMode &= ~(MODE_PHONE1_ONLY << phoneId);
        } else {
            simMode |= (MODE_PHONE1_ONLY << phoneId);
        }

        if (simMode != oldMode) {
            log("Refresh MSIM mode setting to " + simMode + " from " + oldMode);
            Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.MSIM_MODE_SETTING, simMode);
        }
    }

    /*
     * wait ICCID ready when force set radio power
     */
    private class ForceSetRadioPowerRunnable implements Runnable {
        boolean mRetryPower;
        int mRetryPhoneId;
        public  ForceSetRadioPowerRunnable(boolean power, int phoneId) {
            mRetryPower = power;
            mRetryPhoneId = phoneId;
        }
        @Override
        public void run() {
            forceSetRadioPower(mRetryPower, mRetryPhoneId);
        }
    }

    /**
     * force turn on radio and remove iccid for preference to prevent being turned off again
     * 1. For ECC call
     */
    public void forceSetRadioPower(boolean power, int phoneId) {
        log("force set radio power for phone" + phoneId + " ,power: " + power);

        if (isFlightModePowerOffModemEnabled() && mAirplaneMode == AIRPLANE_MODE_ON) {
            log("Force Set Radio Power under airplane mode, ignore");
            return;
        }

        /**
        * We want iccid ready berfore we check if SIM is once manually turned-offedd
        * So we check ICCID repeatedly every 300 ms
        */
        if (!isIccIdReady(phoneId)) {
            log("force set radio power, read iccid not ready, wait for" +
                INITIAL_RETRY_INTERVAL_MSEC + "ms");
            ForceSetRadioPowerRunnable forceSetRadioPowerRunnable =
                new ForceSetRadioPowerRunnable(power, phoneId);
            postDelayed(forceSetRadioPowerRunnable,
                INITIAL_RETRY_INTERVAL_MSEC);
            return;
        }

        boolean radioPower = power;
        refreshIccIdPreference(radioPower, readIccIdUsingPhoneId(phoneId));
        PhoneFactory.getPhone(phoneId).setRadioPower(power);
    }

    /*
     * wait ICCID ready when SIM mode change
     */
    private class SimModeChangeRunnable implements Runnable {
        boolean mPower;
        int mPhoneId;
        public SimModeChangeRunnable(boolean power, int phoneId) {
            mPower = power;
            mPhoneId = phoneId;
        }
        @Override
        public void run() {
            notifySimModeChange(mPower, mPhoneId);
        }
    }

    /**
     * Refresh ICCID preference due to toggling on SIM management except for below cases:
     * 1. SIM Mode Feature not defined
     * 2. Under Airplane Mode (PhoneGlobals will call GSMPhone.setRadioPower after receving airplane mode change)
     * @param power power on -> remove preference
     *               power off -> add to preference
     */
    public void notifySimModeChange(boolean power, int phoneId) {
        log("SIM mode changed, power: " + power + ", phoneId" + phoneId);
        if (!isMSimModeSupport() || mAirplaneMode == AIRPLANE_MODE_ON) {
            log("Airplane mode on or MSIM Mode option is closed, do nothing!");
            return;
        } else {
            if (!isIccIdReady(phoneId)) {
                log("sim mode read iccid not ready, wait for "
                    + INITIAL_RETRY_INTERVAL_MSEC + "ms");
                SimModeChangeRunnable notifySimModeChangeRunnable
                    = new SimModeChangeRunnable(power, phoneId);
                postDelayed(notifySimModeChangeRunnable, INITIAL_RETRY_INTERVAL_MSEC);
                return;
            }
            //once ICCIDs are ready, then set the radio power
            if (STRING_NO_SIM_INSERTED.equals(readIccIdUsingPhoneId(phoneId))) {
                power = RADIO_POWER_OFF;
                log("phoneId " + phoneId + " sim not insert, set  power  to " + power);
            }
            refreshIccIdPreference(power, readIccIdUsingPhoneId(phoneId));
            log("Set Radio Power due to SIM mode change, power: " + power + ", phoneId: " + phoneId);
            PhoneFactory.getPhone(phoneId).setRadioPower(power);         
        }
    }

    /*
     * wait ICCID ready when MSIM modem change
     * @Deprecated
     */
    private class MSimModeChangeRunnable implements Runnable {
        int mRetryMode;
        public  MSimModeChangeRunnable(int mode) {
            mRetryMode = mode;
        }
        @Override
        public void run() {
            notifyMSimModeChange(mRetryMode);
        }
    }

    /**
     * Refresh ICCID preference due to toggling on SIM management except for below cases:
     * 1. SIM Mode Feature not defined
     * 2. Under Airplane Mode (PhoneGlobals will call GSMPhone.setRadioPower after receving airplane mode change)
     * @param power power on -> remove preference
     *               power off -> add to preference
     * @internal
     * @Deprecated
     */
    public void notifyMSimModeChange(int mode) {
        log("MSIM mode changed, mode: " + mode);
        if (mode == -1) {
            log("Invalid mode, MSIM_MODE intent has no extra value");
            return;
        }
        if (!isMSimModeSupport() || mAirplaneMode == AIRPLANE_MODE_ON) {
            log("Airplane mode on or MSIM Mode option is closed, do nothing!");
            return;
        } else {
            //all ICCCIDs need be ready berfore set radio power
            int phoneId = 0;
            boolean iccIdReady = true;
            for (phoneId = 0; phoneId < mPhoneCount; phoneId++) {
                if (!isIccIdReady(phoneId)) {
                    iccIdReady = false;
                    break;
                }
            }
            if (!iccIdReady) {
                log("msim mode read iccid not ready, wait for "
                    + INITIAL_RETRY_INTERVAL_MSEC + "ms");
                MSimModeChangeRunnable notifyMSimModeChangeRunnable
                    = new MSimModeChangeRunnable(mode);
                postDelayed(notifyMSimModeChangeRunnable, INITIAL_RETRY_INTERVAL_MSEC);
                return;
            }
            //once ICCIDs are ready, then set the radio power
            for (phoneId = 0; phoneId < mPhoneCount; phoneId++) {
                boolean singlePhonePower = ((mode & (MODE_PHONE1_ONLY << phoneId)) == 0) ? RADIO_POWER_OFF : RADIO_POWER_ON;
                if (STRING_NO_SIM_INSERTED.equals(readIccIdUsingPhoneId(phoneId))) {
                    singlePhonePower = RADIO_POWER_OFF;
                    log("phoneId " + phoneId + " sim not insert, set  power  to " + singlePhonePower);
                }
                refreshIccIdPreference(singlePhonePower, readIccIdUsingPhoneId(phoneId));
                log("Set Radio Power due to MSIM mode change, power: " + singlePhonePower
                        + ", phoneId: " + phoneId);
                if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                    updateMsimModeRadioPower(singlePhonePower, phoneId);
                } else {
                    PhoneFactory.getPhone(phoneId).setRadioPower(singlePhonePower);
                }
            }
        }
    }

    private void refreshIccIdPreference(boolean power, String iccid) {
        log("refresh iccid preference");
        SharedPreferences.Editor editor = mIccidPreference.edit();
        if (power == RADIO_POWER_OFF && !STRING_NO_SIM_INSERTED.equals(iccid)) {
            putIccIdToPreference(editor, iccid);
        } else {
            removeIccIdFromPreference(editor, iccid);
        }
        editor.commit();
    }

    private void putIccIdToPreference(SharedPreferences.Editor editor, String iccid) {
        if (iccid != null) {
            log("Add radio off SIM: " + iccid);
            editor.putInt(iccid, 0);
         }
    }

    private void removeIccIdFromPreference(SharedPreferences.Editor editor, String iccid) {
        if (iccid != null) {
            log("Remove radio off SIM: " + iccid);
            editor.remove(iccid);
        }
    }

    /*
     * Some Request or AT command must made before EFUN
     * 1. Prevent waiting for response
     * 2. Send commands as the same channel as EFUN or CFUN
     */
    public static void sendRequestBeforeSetRadioPower(boolean power, int phoneId) {
        log("Send request before EFUN, power:" + power + " phoneId:" + phoneId);

        notifyRadioPowerChange(power, phoneId);
    }

    /**
     * MTK Power on feature
     * 1. Radio off a card from SIM Management
     * 2. Flight power off modem
     * @internal
     */
    public static boolean isPowerOnFeatureAllClosed() {
        boolean ret = true;
        if (isFlightModePowerOffModemEnabled()) {
            ret = false;
        } else if (isRadioOffPowerOffModemEnabled()) {
            ret = false;
        } else if (isMSimModeSupport()) {
            ret = false;
        }
        return ret;
    }

    public static boolean isRadioOffPowerOffModemEnabled() {
        return SystemProperties.get("ro.mtk_radiooff_power_off_md").equals("1");
    }

    public static boolean isFlightModePowerOffModemEnabled() {
        if (SystemProperties.get("ril.testmode").equals("1")) {
            return SystemProperties.get("ril.test.poweroffmd").equals("1");
        } else {
            return SystemProperties.get("ro.mtk_flight_mode_power_off_md").equals("1");
        }
    }

    /**
     *  Check if modem is already power off.
     **/
    public static boolean isModemPowerOff(int phoneId) {
        boolean powerOff = false;
        TelephonyManager.MultiSimVariants config
            = TelephonyManager.getDefault().getMultiSimConfiguration();
        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport() &&
                phoneId == SubscriptionManager.LTE_DC_PHONE_ID) {
            log("isModemPowerOff: SVLTE phone id 2 set to 0.");
            phoneId = 0;
        }
        switch(config) {
            case DSDS:
                powerOff = SystemProperties.get("ril.ipo.radiooff").equals("1");
                break;
            case DSDA:
                switch (phoneId) {
                    case 0: //phone 1
                        powerOff = SystemProperties.get("ril.ipo.radiooff").equals("1");
                        break;
                    case 1: //phone 2
                        powerOff = SystemProperties.get("ril.ipo.radiooff.2").equals("1");
                        break;
                    default:
                        powerOff = true;
                        break;
                }
                break;
            case TSTS:
                //TODO: check 3 SIM case
                powerOff = SystemProperties.get("ril.ipo.radiooff").equals("1");
                break;
            default:
                powerOff = SystemProperties.get("ril.ipo.radiooff").equals("1");
                break;
        }
        return powerOff;
    }

    public static boolean isMSimModeSupport() {
        // TODO: adds logic
        if (SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
            return false;
        } else {
            return true;
        }
    }

    private void setAirplaneMode(boolean enabled) {
        log("set mAirplaneMode as:" + enabled);
        mAirplaneMode = enabled;
    }

    private boolean getAirplaneMode() {
        return mAirplaneMode;
    }


    private void resetSimInsertedStatus(int phoneId) {
        log("reset Sim InsertedStatus for Phone:" + phoneId);
        mSimInsertedStatus[phoneId] = SIM_NOT_INITIALIZED;
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        int[] ints;
        String[] strings;
        Message message;
        int phoneIdForMsg = getCiIndex(msg);

        switch (msg.what) {
            case EVENT_VIRTUAL_SIM_ON:
                forceSetRadioPower(RADIO_POWER_ON, phoneIdForMsg);
                break;
            default:
                super.handleMessage(msg);
                break;
        }
    }

    private int getCiIndex(Message msg) {
        AsyncResult ar;
        Integer index = new Integer(PhoneConstants.DEFAULT_CARD_INDEX);

        /*
         * The events can be come in two ways. By explicitly sending it using
         * sendMessage, in this case the user object passed is msg.obj and from
         * the CommandsInterface, in this case the user object is msg.obj.userObj
         */
        if (msg != null) {
            if (msg.obj != null && msg.obj instanceof Integer) {
                index = (Integer)msg.obj;
            } else if(msg.obj != null && msg.obj instanceof AsyncResult) {
                ar = (AsyncResult)msg.obj;
                if (ar.userObj != null && ar.userObj instanceof Integer) {
                    index = (Integer)ar.userObj;
                }
            }
        }
        return index.intValue();
    }

     public static synchronized void registerForRadioPowerChange(String name, IRadioPower iRadioPower) {
        if (name == null) {
            name = NO_NAME;
        }
        log(name + " registerForRadioPowerChange");
        mNotifyRadioPowerChange.put(iRadioPower, name);
    }

    public static synchronized void unregisterForRadioPowerChange(IRadioPower iRadioPower) {
        log(mNotifyRadioPowerChange.get(iRadioPower) + " unregisterForRadioPowerChange");
        mNotifyRadioPowerChange.remove(iRadioPower);
    }

    private static synchronized void notifyRadioPowerChange(boolean power, int phoneId) {
        for (Entry<IRadioPower, String> e : mNotifyRadioPowerChange.entrySet()) {
            log("notifyRadioPowerChange: user:" + e.getValue());
            IRadioPower iRadioPower = e.getKey();
            iRadioPower.notifyRadioPowerChange(power, phoneId);
        }
    }

    private static void log(String s) {
        Rlog.d(LOG_TAG, "[RadioManager] " + s);
    }

}
