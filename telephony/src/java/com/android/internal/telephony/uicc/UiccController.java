/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2011-2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.uicc;
import android.app.ActivityManagerNative;
import android.content.Context;
import static android.Manifest.permission.READ_PHONE_STATE;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.Intent;

import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationManager;

import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.PersoSubState;
import android.os.Bundle;
import android.os.SystemProperties;
import com.mediatek.common.telephony.IUiccControllerExt;
import com.mediatek.common.MPlugin;
import com.mediatek.internal.telephony.cdma.CdmaFeatureOptionUtils;


/**
 * This class is responsible for keeping all knowledge about
 * Universal Integrated Circuit Card (UICC), also know as SIM's,
 * in the system. It is also used as API to get appropriate
 * applications to pass them to phone and service trackers.
 *
 * UiccController is created with the call to make() function.
 * UiccController is a singleton and make() must only be called once
 * and throws an exception if called multiple times.
 *
 * Once created UiccController registers with RIL for "on" and "unsol_sim_status_changed"
 * notifications. When such notification arrives UiccController will call
 * getIccCardStatus (GET_SIM_STATUS). Based on the response of GET_SIM_STATUS
 * request appropriate tree of uicc objects will be created.
 *
 * Following is class diagram for uicc classes:
 *
 *                       UiccController
 *                            #
 *                            |
 *                        UiccCard
 *                          #   #
 *                          |   ------------------
 *                    UiccCardApplication    CatService
 *                      #            #
 *                      |            |
 *                 IccRecords    IccFileHandler
 *                 ^ ^ ^           ^ ^ ^ ^ ^
 *    SIMRecords---- | |           | | | | ---SIMFileHandler
 *    RuimRecords----- |           | | | ----RuimFileHandler
 *    IsimUiccRecords---           | | -----UsimFileHandler
 *                                 | ------CsimFileHandler
 *                                 ----IsimFileHandler
 *
 * Legend: # stands for Composition
 *         ^ stands for Generalization
 *
 * See also {@link com.android.internal.telephony.IccCard}
 * and {@link com.android.internal.telephony.uicc.IccCardProxy}
 */
public class UiccController extends Handler {
    private static final boolean DBG = true;
    private static final String LOG_TAG = "UiccController";

    public static final int APP_FAM_3GPP =  1;
    public static final int APP_FAM_3GPP2 = 2;
    public static final int APP_FAM_IMS   = 3;

    private static final int EVENT_ICC_STATUS_CHANGED = 1;
    private static final int EVENT_GET_ICC_STATUS_DONE = 2;
    private static final int EVENT_RADIO_UNAVAILABLE = 3;
    private static final int EVENT_SIM_REFRESH = 4;

    private static final String DECRYPT_STATE = "trigger_restart_framework";

    protected static final int EVENT_RADIO_AVAILABLE = 100;
    protected static final int EVENT_VIRTUAL_SIM_ON = 101;
    protected static final int EVENT_VIRTUAL_SIM_OFF = 102;
    protected static final int EVENT_SIM_MISSING = 103;
    protected static final int EVENT_QUERY_SIM_MISSING_STATUS = 104;
    protected static final int EVENT_SIM_RECOVERY = 105;
    protected static final int EVENT_GET_ICC_STATUS_DONE_FOR_SIM_MISSING = 106;
    protected static final int EVENT_GET_ICC_STATUS_DONE_FOR_SIM_RECOVERY = 107;
    protected static final int EVENT_QUERY_ICCID_DONE_FOR_HOT_SWAP = 108;
    protected static final int EVENT_SIM_PLUG_OUT = 109;
    protected static final int EVENT_SIM_PLUG_IN = 110;
    protected static final int EVENT_HOTSWAP_GET_ICC_STATUS_DONE = 111;
    protected static final int EVENT_QUERY_SIM_STATUS_FOR_PLUG_IN = 112;
    protected static final int EVENT_QUERY_SIM_MISSING = 113;
    protected static final int EVENT_INVALID_SIM_DETECTED = 114;
    protected static final int EVENT_REPOLL_SML_STATE = 115;
    protected static final int EVENT_COMMON_SLOT_NO_CHANGED = 116;
    protected static final int EVENT_CDMA_CARD_TYPE = 117;

    //Multi-application
    protected static final int EVENT_TURN_ON_ISIM_APPLICATION_DONE = 200;
    protected static final int EVENT_GET_ICC_APPLICATION_STATUS = 201;
    protected static final int EVENT_APPLICATION_SESSION_CHANGED = 202;

    private static final int SML_FEATURE_NO_NEED_BROADCAST_INTENT = 0;
    private static final int SML_FEATURE_NEED_BROADCAST_INTENT = 1;

    /* SIM inserted status constants */
    private static final int STATUS_NO_SIM_INSERTED = 0x00;
    private static final int STATUS_SIM1_INSERTED = 0x01;
    private static final int STATUS_SIM2_INSERTED = 0x02;
    private static final int STATUS_SIM3_INSERTED = 0x04;
    private static final int STATUS_SIM4_INSERTED = 0x08;

    private static final String ACTION_RESET_MODEM = "android.intent.action.sim.ACTION_RESET_MODEM";
    private static final String PROPERTY_3G_SIM = "persist.radio.simswitch";


    private CommandsInterface[] mCis;
    private UiccCard[] mUiccCards = new UiccCard[TelephonyManager.getDefault().getPhoneCount()];

    private static final Object mLock = new Object();
    private static UiccController mInstance;

    private Context mContext;
    private boolean mIsHotSwap = false;
    private boolean mClearMsisdn = false;
    private IccCardConstants.CardType mCdmaCardType = IccCardConstants.CardType.UNKNOW_CARD;

    protected RegistrantList mIccChangedRegistrants = new RegistrantList();
    private RegistrantList mRecoveryRegistrants = new RegistrantList();
    //Multi-application
    private int[] mIsimSessionId = new int[TelephonyManager.getDefault().getPhoneCount()];
    private RegistrantList mApplicationChangedRegistrants = new RegistrantList();

    private int[] UICCCONTROLLER_STRING_NOTIFICATION_SIM_MISSING = {
        com.mediatek.internal.R.string.sim_missing_slot1,
        com.mediatek.internal.R.string.sim_missing_slot2,
        com.mediatek.internal.R.string.sim_missing_slot3,
        com.mediatek.internal.R.string.sim_missing_slot4
    };

    private int[] UICCCONTROLLER_STRING_NOTIFICATION_VIRTUAL_SIM_ON = {
        com.mediatek.internal.R.string.virtual_sim_on_slot1,
        com.mediatek.internal.R.string.virtual_sim_on_slot2,
        com.mediatek.internal.R.string.virtual_sim_on_slot3,
        com.mediatek.internal.R.string.virtual_sim_on_slot4
    };

    private static final String COMMON_SLOT_PROPERTY = "ro.mtk_sim_hot_swap_common_slot";

    private static IUiccControllerExt mUiccControllerExt;

    //C2K SVLTE
    private CommandsInterface mSvlteCi;
    public static final int INDEX_SVLTE = 100;
    private int mSvlteIndex = -1;
    private int mNotifyIccCount = 0;
    private static final String[]  PROPERTY_RIL_FULL_UICC_TYPE = {
        "gsm.ril.fulluicctype",
        "gsm.ril.fulluicctype.2",
        "gsm.ril.fulluicctype.3",
        "gsm.ril.fulluicctype.4",
    };
    private static final int CARD_TYPE_SIM  = 1;
    private static final int CARD_TYPE_USIM = 2;
    private static final int CARD_TYPE_CSIM = 4;
    private static final int CARD_TYPE_RUIM = 8;
    public static UiccController make(Context c, CommandsInterface[] ci) {
        synchronized (mLock) {
            if (mInstance != null) {
                throw new RuntimeException("MSimUiccController.make() should only be called once");
            }
            mInstance = new UiccController(c, ci);
            return (UiccController)mInstance;
        }
    }

    private UiccController(Context c, CommandsInterface []ci) {
        if (DBG) log("Creating UiccController");
        mContext = c;
        mCis = ci;
        for (int i = 0; i < mCis.length; i++) {
            Integer index = new Integer(i);
            mCis[i].registerForIccStatusChanged(this, EVENT_ICC_STATUS_CHANGED, index);
            // MTK-START
            if (!(CdmaFeatureOptionUtils.isCdmaLteDcSupport() && i == 0)) {
            // MTK-END
                // TODO remove this once modem correctly notifies the unsols
                if (SystemProperties.get("ro.crypto.state").equals("unencrypted")
                    || DECRYPT_STATE.equals(SystemProperties.get("vold.decrypt"))) {
                    mCis[i].registerForAvailable(this, EVENT_ICC_STATUS_CHANGED, index);
                } else {
                    mCis[i].registerForOn(this, EVENT_ICC_STATUS_CHANGED, index);
                }
            // MTK-START
            }
            // MTK-END
            mCis[i].registerForNotAvailable(this, EVENT_RADIO_UNAVAILABLE, index);
            mCis[i].registerForIccRefresh(this, EVENT_SIM_REFRESH, index);

            mCis[i].registerForVirtualSimOn(this, EVENT_VIRTUAL_SIM_ON, index);
            mCis[i].registerForVirtualSimOff(this, EVENT_VIRTUAL_SIM_OFF, index);
            mCis[i].registerForSimMissing(this, EVENT_SIM_MISSING, index);
            mCis[i].registerForSimRecovery(this, EVENT_SIM_RECOVERY, index);
            mCis[i].registerForSimPlugOut(this, EVENT_SIM_PLUG_OUT, index);
            mCis[i].registerForSimPlugIn(this, EVENT_SIM_PLUG_IN, index);
            mCis[i].registerForCommonSlotNoChanged(this, EVENT_COMMON_SLOT_NO_CHANGED, index);
            mCis[i].registerForSessionChanged(this, EVENT_APPLICATION_SESSION_CHANGED, index);
            if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()
                && (i == CdmaFeatureOptionUtils.getExternalModemSlot())) {
                mCis[i].registerForCdmaCardType(this, EVENT_CDMA_CARD_TYPE, index);
            }
        }

        IntentFilter filter = new IntentFilter();
        /* TODO: Wait for SIM Info migration done
        filter.addAction(TelephonyIntents.ACTION_SIM_INFO_UPDATE);
        */
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(ACTION_RESET_MODEM);
        if (CdmaFeatureOptionUtils.isEvdoDTSupport()) {
            filter.addAction(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        }
        mContext.registerReceiver(mIntentReceiver, filter);

        try {
            mUiccControllerExt = MPlugin.createInstance(IUiccControllerExt.class.getName(), mContext);
        } catch (Exception e) {
            Rlog.e(LOG_TAG, "Fail to create plug-in");
            e.printStackTrace();
        }
    }

    public static UiccController getInstance() {
        synchronized (mLock) {
            if (mInstance == null) {
                throw new RuntimeException(
                        "UiccController.getInstance can't be called before make()");
            }
            return mInstance;
        }
    }

    public UiccCard getUiccCard(int phoneId) {
        synchronized (mLock) {
            if (isValidCardIndex(phoneId)) {
                return mUiccCards[phoneId];
            }
            return null;
        }
    }

    public UiccCard[] getUiccCards() {
        // Return cloned array since we don't want to give out reference
        // to internal data structure.
        synchronized (mLock) {
            return mUiccCards.clone();
        }
    }

    // Easy to use API
    public UiccCardApplication getUiccCardApplication(int family) {
        return getUiccCardApplication(SubscriptionController.getInstance().getPhoneId(
                SubscriptionController.getInstance().getDefaultSubId()), family);
    }

    public IccRecords getIccRecords(int phoneId, int family) {
        synchronized (mLock) {
            UiccCardApplication app = getUiccCardApplication(phoneId, family);
            if (app != null) {
                return app.getIccRecords();
            }
            return null;
        }
    }

    // Easy to use API
    public IccFileHandler getIccFileHandler(int phoneId, int family) {
        synchronized (mLock) {
            UiccCardApplication app = getUiccCardApplication(phoneId, family);
            if (app != null) {
                return app.getIccFileHandler();
            }
            return null;
        }
    }

    public int getIccApplicationChannel(int slotId, int family) {
        synchronized (mLock) {
            int index = 0;
            switch (family) {
                case UiccController.APP_FAM_IMS:
                    // FIXME: error handling for invaild slotId?
                    index = mIsimSessionId[slotId];
                    break;
                default:
                    if (DBG) log("unknown application");
                    break;
            }
            return index;
        }
    }
    //Notifies when card status changes
    public void registerForIccChanged(Handler h, int what, Object obj) {
        synchronized (mLock) {
            Registrant r = new Registrant (h, what, obj);
            mIccChangedRegistrants.add(r);
            //Notify registrant right after registering, so that it will get the latest ICC status,
            //otherwise which may not happen until there is an actual change in ICC status.
            r.notifyRegistrant();
        }
    }

    public void unregisterForIccChanged(Handler h) {
        synchronized (mLock) {
            mIccChangedRegistrants.remove(h);
        }
    }

    //Notifies when card status changes
    public void registerForIccRecovery(Handler h, int what, Object obj) {
        synchronized (mLock) {
            Registrant r = new Registrant(h, what, obj);
            mRecoveryRegistrants.add(r);
            //Notify registrant right after registering, so that it will get the latest ICC status,
            //otherwise which may not happen until there is an actual change in ICC status.
            r.notifyRegistrant();
        }
    }

    public void unregisterForIccRecovery(Handler h) {
        synchronized (mLock) {
            mRecoveryRegistrants.remove(h);
        }
    }

    @Override
    public void handleMessage (Message msg) {
        synchronized (mLock) {
            Integer index = getCiIndex(msg);

            if (index < 0 || index >= mCis.length) {
                if (indexValidForSvlte(index)) {
                    if (DBG) {
                        log("SVLTE index");
                    }
                } else {
                    Rlog.e(LOG_TAG, "Invalid index : " + index + " received with event "
                        + msg.what);
                    return;
                }
            }
            AsyncResult ar = (AsyncResult)msg.obj;
            switch (msg.what) {
                case EVENT_ICC_STATUS_CHANGED:
                    if (DBG) {
                        log("Received EVENT_ICC_STATUS_CHANGED, calling getIccCardStatus, index: "
                            + index);
                    }
                    if (CdmaFeatureOptionUtils.isCdmaLteDcSupport() && index == INDEX_SVLTE) {
                        int fullType = getFullCardType();
                        updateNotifyIccCount();
                        if (fullType == 0 || (fullType & CARD_TYPE_SIM) != 0
                            || (fullType & CARD_TYPE_USIM) != 0) {
                            if (DBG) {
                                log("EVENT_ICC_STATUS_CHANGED, calling getIccCardStatus via GSM");
                            }
                            mSvlteCi.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE,
                                index));
                        }
                        /*
                        if (mSvlteIndex != -1 &&
                            ((fullType & CARD_TYPE_CSIM) != 0
                                || (fullType & CARD_TYPE_RUIM) != 0)) {
                            if (DBG) {
                                log("EVENT_ICC_STATUS_CHANGED, calling getIccCardStatus via CDMA");
                            }
                            mCis[mSvlteIndex].getIccCardStatus(obtainMessage(
                                EVENT_GET_ICC_STATUS_DONE, mSvlteIndex));
                        }*/
                    } else {
                        mCis[index].getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE,
                            index));
                    }
                    break;
                case EVENT_GET_ICC_STATUS_DONE:
                    if (DBG) log("Received EVENT_GET_ICC_STATUS_DONE");
                    onGetIccCardStatusDone(ar, index);
                    break;
                case EVENT_REPOLL_SML_STATE:
                    if (DBG) log("Received EVENT_REPOLL_SML_STATE");
                    ar = (AsyncResult) msg.obj;
                    boolean needIntent = msg.arg1 == SML_FEATURE_NEED_BROADCAST_INTENT ? true : false;

                    //Update Uicc Card status.
                    onGetIccCardStatusDone(ar, index, false);
                    if (CdmaFeatureOptionUtils.isCdmaLteDcSupport() && index == INDEX_SVLTE) {
                        index = mSvlteIndex;
                    }

                    // If we still in Network lock, broadcast intent if caller need this intent.
                    if (mUiccCards[index] != null && needIntent == true) {
                        UiccCardApplication app = mUiccCards[index].getApplication(APP_FAM_3GPP);
                        if (app == null) {
                            if (DBG) log("UiccCardApplication = null");
                            break;
                        }
                        if (app.getState() == AppState.APPSTATE_SUBSCRIPTION_PERSO) {
                            Intent lockIntent = new Intent();
                            if (null == lockIntent) {
                                if (DBG) log("New intent failed");
                                return;
                            }
                            if (DBG) log("Broadcast ACTION_UNLOCK_SIM_LOCK");
                            lockIntent.setAction(TelephonyIntents.ACTION_UNLOCK_SIM_LOCK);
                            lockIntent.putExtra(IccCardConstants.INTENT_KEY_ICC_STATE,
                                    IccCardConstants.INTENT_VALUE_ICC_LOCKED);
                            lockIntent.putExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON,
                                    parsePersoType(app.getPersoSubState()));
                            SubscriptionManager.putPhoneIdAndSubIdExtra(lockIntent, index);
                            mContext.sendBroadcast(lockIntent);
                        }
                    }
                    break;

                case EVENT_RADIO_UNAVAILABLE:
                    if (DBG) log("EVENT_RADIO_UNAVAILABLE, dispose card");
                    if (mUiccCards[index] != null) {
                        mUiccCards[index].dispose();
                    }
                    mUiccCards[index] = null;
                    mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, index, null));
                    break;

                case EVENT_SIM_REFRESH:
                    if (DBG) log("Received EVENT_SIM_REFRESH");
                    onSimRefresh(ar, index);
                    break;

                case EVENT_TURN_ON_ISIM_APPLICATION_DONE:
                    if (DBG) log("Received EVENT_TURN_ON_ISIM_APPLICATION_DONE");
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        Rlog.e(LOG_TAG, "[SIM " + index + "] Error turn on ISIM. ", ar.exception);
                        mApplicationChangedRegistrants.notifyRegistrants();
                        return;
                    }

                    //Response format: <Application ID>, <Session ID>
                    int[] ints = (int[]) ar.result;
                    if (DBG) log("Application ID = " + ints[0] + "Session ID = " + ints[1]);

                    mIsimSessionId[index] =  ints[1];
                    mCis[index].getIccApplicationStatus(mIsimSessionId[index],
                            obtainMessage(EVENT_GET_ICC_APPLICATION_STATUS, index));
                    break;

                case EVENT_GET_ICC_APPLICATION_STATUS:
                    if (DBG) log("Received EVENT_GET_ICC_APPLICATION_STATUS");
                    ar = (AsyncResult) msg.obj;
                    onGetIccApplicationStatusDone(ar, index);
                    break;

                case EVENT_APPLICATION_SESSION_CHANGED:
                    if (DBG) log("Received EVENT_APPLICATION_SESSION_CHANGED");
                    ar = (AsyncResult) msg.obj;

                    //Response format: <Application ID>, <Session ID>
                    int[] result = (int[]) ar.result;
                    // FIXME: application id and array index? only support one application now.
                    if (DBG) log("Application = " + result[0] + ", Session = " + result[1]);
                    mIsimSessionId[index] =  result[1];
                    break;
                case EVENT_VIRTUAL_SIM_ON:
                    if (DBG) {
                        log("handleMessage (EVENT_VIRTUAL_SIM_ON)");
                    }
                    setNotificationVirtual(index, EVENT_VIRTUAL_SIM_ON);
                    SharedPreferences shOn = mContext.getSharedPreferences("AutoAnswer", 1);
                    SharedPreferences.Editor editorOn = shOn.edit();
                    editorOn.putBoolean("flag", true);
                    editorOn.commit();
                    break;
               case EVENT_VIRTUAL_SIM_OFF:
                    if (DBG) log("handleMessage (EVENT_VIRTUAL_SIM_OFF)");
                    removeNotificationVirtual(index, EVENT_VIRTUAL_SIM_ON);
                    //setNotification(index, EVENT_SIM_MISSING);
                    SharedPreferences shOff = mContext.getSharedPreferences("AutoAnswer", 1);
                    SharedPreferences.Editor editorOff = shOff.edit();
                    editorOff.putBoolean("flag", false);
                    editorOff.commit();
                    break;
                case EVENT_SIM_RECOVERY:
                    if (DBG) log("handleMessage (EVENT_SIM_RECOVERY)");
                    mCis[index].getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE_FOR_SIM_RECOVERY, index));
                    mRecoveryRegistrants.notifyRegistrants(new AsyncResult(null, index, null));
                    //disableSimMissingNotification(index);

                    //ALPS01209124
                    Intent intent = new Intent();
                    intent.setAction(TelephonyIntents.ACTION_SIM_RECOVERY_DONE);
                    mContext.sendBroadcast(intent);
                    break;
                case EVENT_SIM_MISSING:
                    if (DBG) log("handleMessage (EVENT_SIM_MISSING)");
                    //setNotification(index, EVENT_SIM_MISSING);
                    mCis[index].getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE_FOR_SIM_MISSING, index));
                    break;
                case EVENT_GET_ICC_STATUS_DONE_FOR_SIM_MISSING:
                    if (DBG) log("Received EVENT_GET_ICC_STATUS_DONE_FOR_SIM_MISSING");
                    ar = (AsyncResult) msg.obj;
                    onGetIccCardStatusDone(ar, index, false);
                    break;
                case EVENT_GET_ICC_STATUS_DONE_FOR_SIM_RECOVERY:
                    if (DBG) log("Received EVENT_GET_ICC_STATUS_DONE_FOR_SIM_RECOVERY");
                    ar = (AsyncResult) msg.obj;
                    onGetIccCardStatusDone(ar, index, false);
                    break;
                case EVENT_COMMON_SLOT_NO_CHANGED:
                    if (DBG) log("handleMessage (EVENT_COMMON_SLOT_NO_CHANGED)");
                    Intent intentNoChanged = new Intent(TelephonyIntents.ACTION_COMMON_SLOT_NO_CHANGED);
                    int slotId = index.intValue();
                    SubscriptionManager.putPhoneIdAndSubIdExtra(intentNoChanged, slotId);
                    log("Broadcasting intent ACTION_COMMON_SLOT_NO_CHANGED for mSlotId : " + slotId);
                    mContext.sendBroadcast(intentNoChanged);
                    break;
                case EVENT_CDMA_CARD_TYPE:
                    if (DBG) {
                        log("handleMessgage (EVENT_CDMA_CARD_TYPE)");
                    }
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception == null) {
                        int[] resultType = (int[]) ar.result;
                        if (resultType != null) {
                            mCdmaCardType = IccCardConstants.CardType
                                    .getCardTypeFromInt(resultType[0]);
                            broadcastCdmaCardTypeIntent();
                        }
                    }
                    break;
                case EVENT_SIM_PLUG_IN:
                    if (DBG) {
                        log("Received EVENT_SIM_PLUG_IN, index=" + index);
                    }
                    configModemRemoteSimAccess();
                    break;
                case EVENT_SIM_PLUG_OUT:
                    if (DBG) {
                        log("Received EVENT_SIM_PLUG_OUT, index=" + index);
                    }
                    configModemRemoteSimAccess();
                    break;
                default:
                    Rlog.e(LOG_TAG, " Unknown Event " + msg.what);
            }
        }
    }

    private Integer getCiIndex(Message msg) {
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
        return index;
    }

    // Easy to use API
    public UiccCardApplication getUiccCardApplication(int phoneId, int family) {
        synchronized (mLock) {
            if (isValidCardIndex(phoneId)) {
                UiccCard c = mUiccCards[phoneId];
                if (c != null) {
                    return mUiccCards[phoneId].getApplication(family);
                }
            }
            return null;
        }
    }

    private synchronized void onGetIccCardStatusDone(AsyncResult ar, Integer index) {
        if (ar.exception != null) {
            Rlog.e(LOG_TAG,"Error getting ICC status. "
                    + "RIL_REQUEST_GET_ICC_STATUS should "
                    + "never return an error", ar.exception);
            return;
        }
        if (!isValidCardIndex(index)) {
            Rlog.e(LOG_TAG,"onGetIccCardStatusDone: invalid index : " + index);
            return;
        }
        if (DBG) log("onGetIccCardStatusDone, index " + index);

        IccCardStatus status = (IccCardStatus)ar.result;

        /* Follow Android's design, Settings should handle the UE
                if (status.mCardState == IccCardStatus.CardState.CARDSTATE_PRESENT) {
                    if (DBG) log("onGetIccCardStatusDone, disableSimMissingNotification because card is present");
                    disableSimMissingNotification(index);
                } else if (status.mCardState == IccCardStatus.CardState.CARDSTATE_PRESENT) {
                    setNotification(index, EVENT_SIM_MISSING);
                }
                */

        if (index == INDEX_SVLTE) {
            // SVLTE PS
            if (mUiccCards[mSvlteIndex] == null) {
                //Create new card
                log("new SVLTE PS UiccApplication");
                mUiccCards[mSvlteIndex] = new UiccCard(mContext, mCis[mSvlteIndex], status,
                    mSvlteIndex, mSvlteCi);
            } else {
                //Update already existing card
                log("update SVLTE PS UiccApplication");
                //mUiccCards[mSvlteIndex].setSvlteFlag(true);
                mUiccCards[mSvlteIndex].update(mContext, mCis[mSvlteIndex] , status);
                mUiccCards[mSvlteIndex].registerCdmaCardImsiDone(index);
            }
        } else if (index == mSvlteIndex) {
            //SVLTE CS
            if (mUiccCards[mSvlteIndex] == null) {
                //Create new card
                log("new SVLTE CS UiccApplication");
                mUiccCards[mSvlteIndex] = new UiccCard(mContext, mCis[mSvlteIndex], status,
                    mSvlteIndex, mSvlteCi);
            } else {
                //Update already existing card
                log("update SVLTE CS UiccApplication");
                //mUiccCards[mSvlteIndex].setSvlteFlag(true);
                mUiccCards[mSvlteIndex].update(mContext, mCis[mSvlteIndex] , status);
                mUiccCards[mSvlteIndex].registerCdmaCardImsiDone(index);
            }
        } else {
            // common flow
            if (mUiccCards[index] == null) {
                //Create new card
                log("new UiccApplication index=" + index);
                mUiccCards[index] = new UiccCard(mContext, mCis[index], status, index);

/*
            // Update the UiccCard in base class, so that if someone calls
            // UiccManager.getUiccCard(), it will return the default card.
            if (index == PhoneConstants.DEFAULT_CARD_INDEX) {
                mUiccCard = mUiccCards[index];
            }
*/
            } else {
                //Update already existing card
                log("update UiccApplication index=" + index);
                mUiccCards[index].update(mContext, mCis[index] , status);
            }
        }

        if (DBG) log("Notifying IccChangedRegistrants");
        mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, index, null));

    }

    private void onSimRefresh(AsyncResult ar, Integer index) {
        if (ar.exception != null) {
            Rlog.e(LOG_TAG, "Sim REFRESH with exception: " + ar.exception);
            return;
        }

        if (!isValidCardIndex(index)) {
            Rlog.e(LOG_TAG,"onSimRefresh: invalid index : " + index);
            return;
        }

        IccRefreshResponse resp = (IccRefreshResponse) ar.result;
        Rlog.d(LOG_TAG, "onSimRefresh: " + resp);

        if (mUiccCards[index] == null) {
            Rlog.e(LOG_TAG,"onSimRefresh: refresh on null card : " + index);
            return;
        }

        if (resp.refreshResult != IccRefreshResponse.REFRESH_RESULT_RESET ||
            resp.aid == null) {
            Rlog.d(LOG_TAG, "Ignoring reset: " + resp);
            return;
        }

        boolean changed = mUiccCards[index].resetAppWithAid(resp.aid);
        if (changed) {
            boolean requirePowerOffOnSimRefreshReset = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_requireRadioPowerOffOnSimRefreshReset);
            if (requirePowerOffOnSimRefreshReset) {
                mCis[index].setRadioPower(false, null);
            } else {
                mCis[index].getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE));
            }
            mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, index, null));
        }
        // TODO: For a card level notification, we should delete the CarrierPrivilegeRules and the
        // CAT service.
    }

    private boolean isValidCardIndex(int index) {
        return (index >= 0 && index < mUiccCards.length)
            || (index == INDEX_SVLTE && CdmaFeatureOptionUtils.isCdmaLteDcSupport());
    }

    private void log(String string) {
        Rlog.d(LOG_TAG, string);
    }


    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("UiccController: " + this);
        pw.println(" mContext=" + mContext);
        pw.println(" mInstance=" + mInstance);
        pw.println(" mIccChangedRegistrants: size=" + mIccChangedRegistrants.size());
        for (int i = 0; i < mIccChangedRegistrants.size(); i++) {
            pw.println("  mIccChangedRegistrants[" + i + "]="
                    + ((Registrant)mIccChangedRegistrants.get(i)).getHandler());
        }
        pw.println();
        pw.flush();
        pw.println(" mUiccCards: size=" + mUiccCards.length);
        for (int i = 0; i < mUiccCards.length; i++) {
            if (mUiccCards[i] == null) {
                pw.println("  mUiccCards[" + i + "]=null");
            } else {
                pw.println("  mUiccCards[" + i + "]=" + mUiccCards[i]);
                mUiccCards[i].dump(fd, pw, args);
            }
        }
    }

    // Added by M begin
    private synchronized void onGetIccCardStatusDone(AsyncResult ar, Integer index, boolean isUpdate) {
        if (ar.exception != null) {
            Rlog.e(LOG_TAG, "Error getting ICC status. "
                    + "RIL_REQUEST_GET_ICC_STATUS should "
                    + "never return an error", ar.exception);
            return;
        }
        if (!isValidCardIndex(index)) {
            Rlog.e(LOG_TAG, "onGetIccCardStatusDone: invalid index : " + index);
            return;
        }
        if (DBG) log("onGetIccCardStatusDone, index " + index + "isUpdateSiminfo " + isUpdate);

        IccCardStatus status = (IccCardStatus) ar.result;

        //if (status.mCardState == IccCardStatus.CardState.CARDSTATE_PRESENT) {
        //    if (DBG) log("onGetIccCardStatusDone, disableSimMissingNotification because card is present");
        //    disableSimMissingNotification(index);
        //}

        if (index == INDEX_SVLTE) {
            // SVLTE PS
            if (mUiccCards[mSvlteIndex] == null) {
                //Create new card
                log("new SVLTE PS UiccApplication");
                mUiccCards[mSvlteIndex] = new UiccCard(mContext, mCis[mSvlteIndex], status,
                    mSvlteIndex, mSvlteCi);
            } else {
                //Update already existing card
                log("update SVLTE PS UiccApplication");
                //mUiccCards[mSvlteIndex].setSvlteFlag(true);
                mUiccCards[mSvlteIndex].update(mContext, mCis[mSvlteIndex] , status);
            }
        } else if (index == mSvlteIndex) {
            //SVLTE CS
            if (mUiccCards[mSvlteIndex] == null) {
                //Create new card
                log("new SVLTE CS UiccApplication");
                mUiccCards[mSvlteIndex] = new UiccCard(mContext, mCis[mSvlteIndex], status,
                    mSvlteIndex, mSvlteCi);
            } else {
                //Update already existing card
                log("update SVLTE CS UiccApplication");
                //mUiccCards[mSvlteIndex].setSvlteFlag(true);
                mUiccCards[mSvlteIndex].update(mContext, mCis[mSvlteIndex] , status);
            }
        } else {
            // common flow
            if (mUiccCards[index] == null) {
                //Create new card
                log("new UiccApplication index=" + index);
                mUiccCards[index] = new UiccCard(mContext, mCis[index], status, index);

/*
            // Update the UiccCard in base class, so that if someone calls
            // UiccManager.getUiccCard(), it will return the default card.
            if (index == PhoneConstants.DEFAULT_CARD_INDEX) {
                mUiccCard = mUiccCards[index];
            }
*/
            } else {
                //Update already existing card
                log("update UiccApplication index=" + index);
                mUiccCards[index].update(mContext, mCis[index] , status, isUpdate);
            }
        }

        if (DBG) log("Notifying IccChangedRegistrants");
        // TODO: Think if it is possible to pass isUpdate
        if (!SystemProperties.get(COMMON_SLOT_PROPERTY).equals("1")) {
            mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, index, null));
        } else {
            Bundle result = new Bundle();
            result.putInt("Index", index.intValue());
            result.putBoolean("ForceUpdate", isUpdate);

            mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, result, null));
        }
    }

    private void setNotification(int slot, int notifyType) {
        if (DBG) log("setNotification(): notifyType = " + notifyType);
        Notification notification = new Notification();
        notification.when = System.currentTimeMillis();
        notification.flags = Notification.FLAG_AUTO_CANCEL;
        notification.icon = com.android.internal.R.drawable.stat_sys_warning;
        Intent intent = new Intent();
        notification.contentIntent = PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        String title = mUiccControllerExt.getMissingTitle(mContext, slot);
        CharSequence detail = mUiccControllerExt.getMissingDetail(mContext);

        notification.tickerText = title;
        notification.setLatestEventInfo(mContext, title, detail, notification.contentIntent);
        NotificationManager notificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(notifyType + slot, notification);
    }

    // ALPS00294581
    public void disableSimMissingNotification(int slot) {
        NotificationManager notificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(EVENT_SIM_MISSING + slot);
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            log("mIntentReceiver Receive action " + action);

            if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(intent.getAction())) {
                log(intent.toString() + intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE));
                String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                int slot = intent.getIntExtra(PhoneConstants.SLOT_KEY, PhoneConstants.SIM_ID_1);
                log("mIntentReceiver ACTION_SIM_STATE_CHANGED slot " + slot + " ,state " + stateExtra);

                if (slot >= TelephonyManager.getDefault().getPhoneCount()) {
                    Rlog.e(LOG_TAG, "BroadcastReceiver SIM State changed slot is invalid");
                    return;
                }

                String iccType = ((getUiccCard(slot) != null) ? getUiccCard(slot).getIccCardType() : "");

                if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(stateExtra)
                        && "USIM".equals(iccType)) {
                    mCis[slot].openIccApplication(0, obtainMessage(EVENT_TURN_ON_ISIM_APPLICATION_DONE, slot));
                }
            } else if (action.equals(ACTION_RESET_MODEM)) {
                int phoneIdFor3G = SystemProperties.getInt(PROPERTY_3G_SIM, 1);
                if (phoneIdFor3G <= 0 || phoneIdFor3G > mCis.length) {
                    log("Receive ACTION_RESET_MODEM: invalid phone id." + phoneIdFor3G);
                    return;
                }
                log("phone " + phoneIdFor3G + " will reset modem");
                mCis[phoneIdFor3G - 1].resetRadio(null);
            } /* else if (TelephonyIntents.ACTION_SIM_INFO_UPDATE.equals(action)) {
                //ALPS00776430: Since EF_MSISDN can not be read/wrtie without verify PIN.
                //We need to clear it or update it to avoid user to get the cached data before.
                new Thread() {
                    @Override
                    public void run() {
                        SIMInfo simInfo = SIMInfo.getSIMInfoBySlot(mContext, mSimId);
                        if (simInfo!= null && mClearMsisdn == false) {
                            mClearMsisdn = true;
                            log("Initial sim info.");
                            IccRecords iccRecord = getIccRecords(APP_FAM_3GPP);
                            if(iccRecord != null) {
                                SIMInfo.setNumber(mContext, iccRecord.getMsisdnNumber(), simInfo.mSimId);
                            } else {
                                SIMInfo.setNumber(mContext, "", simInfo.mSimId);
                            }
                            Intent intent = new Intent(TelephonyIntents.ACTION_SIM_INFO_UPDATE);
                            ActivityManagerNative.broadcastStickyIntent(intent, READ_PHONE_STATE, UserHandle.USER_ALL);
                        }
                    }
                }.start();
            } */
        }
    };

    private void setNotificationVirtual(int slot, int notifyType) {
/* Vanzo:fangfangjie on: Mon, 21 Oct 2013 22:16:20 +0800
 * implement #52420 not display notification
 */
        if (SystemProperties.getBoolean("ro.init.no_sim_missing_notify", false)) return;
// End of Vanzo:fangfangjie
        if (DBG) log("setNotificationVirtual(): notifyType = " + notifyType);
        Notification notification = new Notification();
        notification.when = System.currentTimeMillis();
        notification.flags = Notification.FLAG_AUTO_CANCEL;
        notification.icon = com.android.internal.R.drawable.stat_sys_warning;
        Intent intent = new Intent();
        notification.contentIntent = PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        String title = null;

        if (TelephonyManager.getDefault().getSimCount() > 1) {
            title = Resources.getSystem().getText(UICCCONTROLLER_STRING_NOTIFICATION_VIRTUAL_SIM_ON[slot]).toString();
        } else {
            title = Resources.getSystem().getText(com.mediatek.internal.R.string.virtual_sim_on).toString();
        }
        CharSequence detail = mContext.getText(com.mediatek.internal.R.string.virtual_sim_on).toString();
        notification.tickerText = mContext.getText(com.mediatek.internal.R.string.virtual_sim_on).toString();

        notification.setLatestEventInfo(mContext, title, detail, notification.contentIntent);
        NotificationManager notificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(notifyType + slot, notification);
    }

    private void removeNotificationVirtual(int slot, int notifyType) {
        NotificationManager notificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(notifyType + slot);
    }

    private synchronized void onGetIccApplicationStatusDone(AsyncResult ar, Integer index) {
        if (ar.exception != null) {
            Rlog.e(LOG_TAG, "Error getting ICC status. "
                    + "RIL_REQUEST_GET_ICC_APPLICATION_STATUS should "
                    + "never return an error", ar.exception);
            return;
        }
        if (!isValidCardIndex(index)) {
            Rlog.e(LOG_TAG, "onGetIccApplicationStatusDone: invalid index : " + index);
            return;
        }
        if (DBG) log("onGetIccApplicationStatusDone, index " + index);

        IccCardStatus status = (IccCardStatus) ar.result;

        if (mUiccCards[index] == null) {
            //Create new card
            mUiccCards[index] = new UiccCard(mContext, mCis[index], status, index);

/*
            // Update the UiccCard in base class, so that if someone calls
            // UiccManager.getUiccCard(), it will return the default card.
            if (index == PhoneConstants.DEFAULT_CARD_INDEX) {
                mUiccCard = mUiccCards[index];
            }
*/
        } else {
            //Update already existing card
            mUiccCards[index].update(mContext, mCis[index] , status);
        }

        if (DBG) log("Notifying mApplicationChangedRegistrants");
        mApplicationChangedRegistrants.notifyRegistrants(new AsyncResult(null, index, null));
    }

    private int mBtSlotId = -1;

    /**
     * Get BT connected sim id.
     *
     * @internal
     */
    public int getBtConnectedSimId() {
        if (DBG) log("getBtConnectedSimId, slot " + mBtSlotId);
        return mBtSlotId;
    }

    /**
     * Set BT connected sim id.
     *
     * @internal
     */
    public void setBtConnectedSimId(int simId) {
        mBtSlotId = simId;
        if (DBG) log("setBtConnectedSimId, slot " + mBtSlotId);
    }

    /**
     * Parse network lock reason string.
     *
     * @param state network lock type
     * @return network lock string
     *
     */
    private String parsePersoType(PersoSubState state) {
        if (DBG) log("parsePersoType, state = " + state);
        switch (state) {
            case PERSOSUBSTATE_SIM_NETWORK:
                return IccCardConstants.INTENT_VALUE_LOCKED_NETWORK;
            case PERSOSUBSTATE_SIM_NETWORK_SUBSET:
                return IccCardConstants.INTENT_VALUE_LOCKED_NETWORK_SUBSET;
            case PERSOSUBSTATE_SIM_CORPORATE:
                return IccCardConstants.INTENT_VALUE_LOCKED_CORPORATE;
            case PERSOSUBSTATE_SIM_SERVICE_PROVIDER:
                return IccCardConstants.INTENT_VALUE_LOCKED_SERVICE_PROVIDER;
            case PERSOSUBSTATE_SIM_SIM:
                return IccCardConstants.INTENT_VALUE_LOCKED_SIM;
            default:
                break;
        }
        return IccCardConstants.INTENT_VALUE_ICC_UNKNOWN;
    }

    //Modem SML change feature.
    public void repollIccStateForModemSmlChangeFeatrue(int slotId, boolean needIntent) {
        if (DBG) log("repollIccStateForModemSmlChangeFeatrue, needIntent = " + needIntent);
        int arg1 = needIntent == true ? SML_FEATURE_NEED_BROADCAST_INTENT : SML_FEATURE_NO_NEED_BROADCAST_INTENT;
        //Use arg1 to determine the intent is needed or not
        //Use object to indicated slotId
        mCis[slotId].getIccCardStatus(obtainMessage(EVENT_REPOLL_SML_STATE, arg1, 0, slotId));
    }

    //Notifies when application status changes
    public void registerForApplicationChanged(Handler h, int what, Object obj) {
        synchronized (mLock) {
            Registrant r = new Registrant(h, what, obj);
            mApplicationChangedRegistrants.add(r);
            //Notify registrant right after registering, so that it will get the latest application status,
            //otherwise which may not happen until there is an actual change in application status.
            r.notifyRegistrant();
        }
    }

    public void unregisterForApplicationChanged(Handler h) {
        synchronized (mLock) {
            mApplicationChangedRegistrants.remove(h);
        }
    }
    // Added by M end

    //C2K SVLTE
    /**
      * Set svlte command interface.
      * @param ci CommandsInterface
      */
    public void setSvlteCi(CommandsInterface ci) {
        if (DBG) {
            log("init SVLTE UiccController!");
        }
        mSvlteCi = ci;
        mSvlteCi.registerForIccStatusChanged(this, EVENT_ICC_STATUS_CHANGED, INDEX_SVLTE);
        mSvlteCi.registerForAvailable(this, EVENT_ICC_STATUS_CHANGED, INDEX_SVLTE);
        mSvlteCi.registerForSimPlugOut(this, EVENT_SIM_PLUG_OUT, INDEX_SVLTE);
        mSvlteCi.registerForSimPlugIn(this, EVENT_SIM_PLUG_IN, INDEX_SVLTE);
    }
    /**
      * Set svlte index.
      * @param index card index
      */
    public void setSvlteIndex(int index) {
        if (index < 0 || index >= mCis.length) {
            log("setSvlteIndex invalid index:" + index);
            return;
        }
        mSvlteIndex = index;
    }

    private boolean indexValidForSvlte(int index) {
        return (index == INDEX_SVLTE && CdmaFeatureOptionUtils.isCdmaLteDcSupport());
    }

    /**
      * Get card type.
      * @return IccCardConstants CardType
      */
    public IccCardConstants.CardType getCardType() {
        if (DBG) {
            log("getCardType mCdmaCardType=" + mCdmaCardType);
        }
        return mCdmaCardType;
    }

    private void broadcastCdmaCardTypeIntent() {
        Intent intent = new Intent(TelephonyIntents.ACTION_CDMA_CARD_TYPE);
        intent.putExtra(TelephonyIntents.INTENT_KEY_CDMA_CARD_TYPE, mCdmaCardType);
        if (DBG) {
            log("Broadcasting intent ACTION_CDMA_CARD_TYPE cardType=" + mCdmaCardType);
        }
        ActivityManagerNative.broadcastStickyIntent(intent, READ_PHONE_STATE, UserHandle.USER_ALL);
    }

    private int getFullCardType() {
        String cardType = SystemProperties.get(PROPERTY_RIL_FULL_UICC_TYPE[0]);
        Rlog.d(LOG_TAG, "getFullCardType=" + cardType);
        String appType[] = cardType.split(",");
        int fullType = 0;
        for (int i = 0; i < appType.length; i++) {
            if ("USIM".equals(appType[i])) {
                fullType = fullType | CARD_TYPE_USIM;
            } else if ("SIM".equals(appType[i])) {
                fullType = fullType | CARD_TYPE_SIM;
            } else if ("CSIM".equals(appType[i])) {
                fullType = fullType | CARD_TYPE_CSIM;
            } else if ("RUIM".equals(appType[i])) {
                fullType = fullType | CARD_TYPE_RUIM;
            }
        }
        Rlog.d(LOG_TAG, "fullType=" + fullType);
        return fullType;
    }

    private void updateNotifyIccCount() {
        int fullType = getFullCardType();

        if (((fullType & CARD_TYPE_RUIM) == CARD_TYPE_RUIM
            || (fullType & CARD_TYPE_CSIM) == CARD_TYPE_CSIM)
            && ((fullType & CARD_TYPE_SIM) == CARD_TYPE_SIM
            || (fullType & CARD_TYPE_USIM) == CARD_TYPE_USIM)) {
            mNotifyIccCount = 2;
        } else {
            mNotifyIccCount = 1;
        }
    }

    private void configModemRemoteSimAccess() {
        int fullType = getFullCardType();

        if (fullType == 0) {
            // no card
            Rlog.d(LOG_TAG, "SIM hot plug configModemStatus: no card");
            for (int i = 0; i < mCis.length; i++) {
                mCis[i].configModemStatus(1, 1, null);
            }
            if (mSvlteCi != null) {
                mSvlteCi.configModemStatus(1, 1, null);
            }
        } else if ((fullType & CARD_TYPE_RUIM) == 0 && (fullType & CARD_TYPE_CSIM) == 0) {
            // GSM only
            Rlog.d(LOG_TAG, "SIM hot plug configModemStatus: GSM only");
            for (int i = 0; i < mCis.length; i++) {
                mCis[i].configModemStatus(0, 0, null);
            }
            if (mSvlteCi != null) {
                mSvlteCi.configModemStatus(0, 0, null);
            }
        } else if (((fullType & CARD_TYPE_SIM) == 0 && (fullType & CARD_TYPE_USIM) == 0)
            || ((fullType & CARD_TYPE_SIM) == CARD_TYPE_SIM
            && (fullType & CARD_TYPE_RUIM) == CARD_TYPE_RUIM)) {
            // CDMA only
            // 1. no SIM and no USIM
            // 2. RUIM and SIM (CT 3G card)
            Rlog.d(LOG_TAG, "SIM hot plug configModemStatus: CDMA only");
            for (int i = 0; i < mCis.length; i++) {
                mCis[i].configModemStatus(1, 1, null);
            }
            if (mSvlteCi != null) {
                mSvlteCi.configModemStatus(1, 1, null);
            }
        } else if ((fullType & CARD_TYPE_USIM) == CARD_TYPE_USIM
            && (fullType & CARD_TYPE_CSIM) == CARD_TYPE_CSIM) {
            // CT LTE
            Rlog.d(LOG_TAG, "SIM hot plug configModemStatus: CT LTE");
            for (int i = 0; i < mCis.length; i++) {
                mCis[i].configModemStatus(2, 1, null);
            }
            if (mSvlteCi != null) {
                mSvlteCi.configModemStatus(2, 1, null);
            }
        } else {
            //other case, may not happen!
            Rlog.d(LOG_TAG, "SIM hot plug configModemStatus: other case, may not happen!");
        }
    }
}
