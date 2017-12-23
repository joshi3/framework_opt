package com.mediatek.internal.telephony.dataconnection;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.WindowManager;

import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.uicc.UiccController;

import com.mediatek.internal.R;

/**
 * Customization from CT for data when roaming.
 * 1, Popup reminder dialog when roaming first time.
 * 2, Update prefer APN according current rat and area.
 */
public class DataRoamingCustomization extends Handler {
    private static final String TAG = "DataRoamingCustomization";

    private static final int EVENT_DATA_OR_ROAMING_SETTING_CHANGED = 1;
    private static final int EVENT_SVLTE_SERVICE_STATE_CHANGED = 2;

    private static final String PREFER_APN_CTNET = "ctnet";
    private static final String PREFER_APN_CTLTE = "ctlte";
    private static final String PREFER_APN_UNKNOWN = "unknown";

    private static final String CHINA_MCC = "460";

    private static final int APP_FAM_UNKNOWN = 0;

    private static final Uri PREFERAPN_NO_UPDATE_URI_USING_SUBID =
            Uri.parse("content://telephony/carriers/preferapn_no_update/subId/");
    private static final String APN_ID = "apn_id";

    private static final String PREFERENCE_NAME = "roaming_customization";
    private static final String FIRST_ROAMING_KEY = "first_roaming";
    private static final String LAST_REG_STATE_KEY = "last_reg_state";
    private static final String LAST_OPERATOR_NUMERIC_KEY = "last_operator_numeric";

    private static final String OPERATOR_OP09 = "OP09";

    private static final int APN_AUTO_MODE = 0;
    private static final int APN_MANUAL_MODE = 1;

    private Context mContext;
    private ContentResolver mResolver;
    private PhoneBase mPhone;

    private int mCurRegState = ServiceState.REGISTRATION_STATE_NOT_REGISTERED_AND_NOT_SEARCHING;
    private String mCurOpNumeric = "00000";

    private ContentObserver mObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            logd("Observer Onchange");
            removeMessages(EVENT_DATA_OR_ROAMING_SETTING_CHANGED);
            sendEmptyMessage(EVENT_DATA_OR_ROAMING_SETTING_CHANGED);
        }
    };

    /**
     * Construct DataRoamingCustomization with context and phone.
     * @param context the context
     * @param phone the phone align the customization
     */
    public DataRoamingCustomization(Context context, PhoneBase phone) {
        String operator = SystemProperties.get("ro.operator.optr", "");
        if (operator != null && operator.equals(OPERATOR_OP09)) {
            logd("DataRoamingCustomization constructor");
            mContext = context;
            mPhone = phone;
            mResolver = mContext.getContentResolver();
            SharedPreferences roamingPreferences = mContext.getSharedPreferences(
                    PREFERENCE_NAME, 0);
            mCurRegState = roamingPreferences.getInt(LAST_REG_STATE_KEY,
                    ServiceState.REGISTRATION_STATE_NOT_REGISTERED_AND_NOT_SEARCHING);
            mCurOpNumeric = roamingPreferences.getString(LAST_OPERATOR_NUMERIC_KEY, "00000");

            mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.MOBILE_DATA),
                    false, mObserver);
            mPhone.registerForSvlteServiceStateChanged(
                    this, EVENT_SVLTE_SERVICE_STATE_CHANGED, null);
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
        case EVENT_DATA_OR_ROAMING_SETTING_CHANGED:
            checkFirstRoaming();
            break;
        case EVENT_SVLTE_SERVICE_STATE_CHANGED:
            AsyncResult ar = (AsyncResult) msg.obj;
            ServiceState serviceState = (ServiceState) ar.result;
            final int regState = serviceState.getRegState();
            final String operatorNumeric = serviceState.getOperatorNumeric();
            logd("EVENT_SVLTE_SERVICE_STATE_CHANGED: regState = " + regState + ",operatorNumeric = "
                    + operatorNumeric + ",mCurRegState = " + mCurRegState + ",mCurOpNumeric = "
                    + mCurOpNumeric);
            if (regState != mCurRegState ||
                    (mCurOpNumeric != null && operatorNumeric != null &&
                    !mCurOpNumeric.equals(operatorNumeric))) {
                if (regState == ServiceState.REGISTRATION_STATE_ROAMING) {
                    saveLastRegInfo(regState, operatorNumeric);
                    checkFirstRoaming();
                    updatePreferedApn();
                } else if (regState == ServiceState.REGISTRATION_STATE_HOME_NETWORK) {
                    saveLastRegInfo(regState, operatorNumeric);
                    setFirstRoamingFlag(true);
                    updatePreferedApn();
                }
            }
            break;
        default:
            break;
        }
    }

    private void checkFirstRoaming() {
        boolean userDataEnabled = Settings.Global.getInt(mResolver,
                    Settings.Global.MOBILE_DATA, 1) == 1;
        boolean isRoaming = mCurRegState == ServiceState.REGISTRATION_STATE_ROAMING;
        SharedPreferences roamingPreferences = mContext.getSharedPreferences(PREFERENCE_NAME, 0);
        boolean firstRoaming = roamingPreferences.getBoolean(FIRST_ROAMING_KEY, true);

        SubscriptionController subController = SubscriptionController.getInstance();
        int defaultDataSub = subController.getDefaultDataSubId();
        int defaultDataSlot = SubscriptionManager.getSlotId(defaultDataSub);
        boolean isDefaultDataSim = defaultDataSlot == PhoneConstants.SIM_ID_1;

        logd("checkFirstRoaming, userDataEnabled=" + userDataEnabled + ",isRoaming="
                + isRoaming + ",firstRoaming=" + firstRoaming
                + ",defaultDataSub=" + defaultDataSub + ",defaultDataSlot=" + defaultDataSlot);
        if (userDataEnabled && isRoaming && firstRoaming && isDefaultDataSim) {
            popupDialog();
            setFirstRoamingFlag(false);
        }
    }

    private void setFirstRoamingFlag(boolean roaming) {
        logd("setFirstRoamingFlag, roaming=" + roaming);
        SharedPreferences roamingPreferences = mContext.getSharedPreferences(PREFERENCE_NAME, 0);
        Editor roamingEditor = roamingPreferences.edit();
        roamingEditor.putBoolean(FIRST_ROAMING_KEY, roaming);
        roamingEditor.commit();
    }

    private void saveLastRegInfo(int regState, String operatorNumeric) {
        logd("saveLastRegInfo, regState=" + regState + ",operatorNumeric=" + operatorNumeric);
        mCurRegState = regState;
        mCurOpNumeric = operatorNumeric;
        SharedPreferences roamingPreferences = mContext.getSharedPreferences(PREFERENCE_NAME, 0);
        Editor roamingEditor = roamingPreferences.edit();
        roamingEditor.putInt(LAST_REG_STATE_KEY, regState);
        roamingEditor.putString(LAST_OPERATOR_NUMERIC_KEY, operatorNumeric);
        roamingEditor.commit();
    }

    /**
     * Unregister from all events it registered for.
     */
    public void dispose() {
        String operator = SystemProperties.get("ro.operator.optr", "");
        if (operator != null && operator.equals(OPERATOR_OP09)) {
            mResolver.unregisterContentObserver(mObserver);
            mPhone.unregisterForSvlteServiceStateChanged(this);
        }
    }

    private void popupDialog() {
        logd("popupDialog for data enabled on roaming network.");
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setMessage(R.string.roaming_message);
//Fix build fail
//        builder.setPositiveButton(R.string.known, null);
        AlertDialog dialog = builder.create();
        dialog.getWindow().setType(
                WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        dialog.show();
    }

    private void updatePreferedApn() {
        String plmnNumeric = TelephonyManager.getTelephonyProperty(
            mPhone.getPhoneId(), TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC, "");
        int apnId = -1;
        String preferApnName = PREFER_APN_CTNET;

        logd("updatePreferedApn, plmnNumeric = " + plmnNumeric);

        if (plmnNumeric != null && !plmnNumeric.equals("")) {
            if (plmnNumeric.startsWith(CHINA_MCC)) {
                // China CT card
                int dataRat = mPhone.getServiceState().getRilDataRadioTechnology();
                preferApnName = getPreferApnNameByRat(dataRat);
                logd("updatePreferedApn, preferApnName = " + preferApnName);
            }
            apnId = getApnIdByName(preferApnName, plmnNumeric);
        }
        logd("updatePreferedApn, apnId = " + apnId);

        // set prefered apn
        setPreferredApn(apnId);
    }

    private String getPreferApnNameByRat(int rat) {
        final int family = getUiccFamilyByRat(rat);
        logd("getPreferApnNameByRat rat = " + rat + ",family = " + family);
        if (family == UiccController.APP_FAM_3GPP) {
            return PREFER_APN_CTLTE;
        } else if (family == UiccController.APP_FAM_3GPP2) {
            return PREFER_APN_CTNET;
        } else {
            return PREFER_APN_UNKNOWN;
        }
    }

    private static int getUiccFamilyByRat(int radioTech) {
        if (radioTech == ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN) {
            return APP_FAM_UNKNOWN;
        }

        if ((radioTech >= ServiceState.RIL_RADIO_TECHNOLOGY_IS95A &&
                radioTech <= ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A)
                || radioTech == ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B) {
            return UiccController.APP_FAM_3GPP2;
        } else {
            return UiccController.APP_FAM_3GPP;
        }
    }

    private int getApnIdByName(String apnName, String plmn) {
        logd("getApnIdByName: apnName  = " + apnName);

        int apnId = -1;
        String selection = "apn = '" + apnName + "'" + " and numeric = '" + plmn + "'";
        logd("getApnIdByName: selection = " + selection);

        Cursor cursor = null;
        try {
            cursor = mResolver.query(
                   Telephony.Carriers.CONTENT_URI, null, selection, null, null);

            if (cursor != null && cursor.getCount() > 0) {
                cursor.moveToFirst();
                apnId = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID));
                logd("getApnIdByName: found, the apn id is:" + apnId);
                return apnId;
                }
        }  finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        logd("getApnIdByName: X not found");
        return -1;
    }

    private void setPreferredApn(int pos) {
        String subId = Long.toString(mPhone.getSubId());
        Uri uri = Uri.withAppendedPath(PREFERAPN_NO_UPDATE_URI_USING_SUBID, subId);
        logd("setPreferredApn: delete");
        ContentResolver resolver = mPhone.getContext().getContentResolver();
        resolver.delete(uri, null, null);

        if (pos >= 0) {
            logd("setPreferredApn: insert");
            ContentValues values = new ContentValues();
            values.put(APN_ID, pos);
            resolver.insert(uri, values);
        }
    }

    private void logd(String s) {
        Rlog.d(TAG, s);
    }
}
