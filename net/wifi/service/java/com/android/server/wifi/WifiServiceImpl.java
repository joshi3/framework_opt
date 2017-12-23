/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.wifi;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.DhcpResults;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.wifi.*;
import android.net.wifi.IWifiManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;

import java.io.FileNotFoundException;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.Override;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.AsyncChannel;
import com.android.server.am.BatteryStatsService;
//import com.android.server.wifi.passpoint.WifiPasspointServiceImpl;
//import com.android.server.wifi.passpoint.WifiPasspointStateMachine;

import static com.android.server.wifi.WifiController.CMD_AIRPLANE_TOGGLED;
import static com.android.server.wifi.WifiController.CMD_BATTERY_CHANGED;
import static com.android.server.wifi.WifiController.CMD_EMERGENCY_MODE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_LOCKS_CHANGED;
import static com.android.server.wifi.WifiController.CMD_SCAN_ALWAYS_MODE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_SCREEN_OFF;
import static com.android.server.wifi.WifiController.CMD_SCREEN_ON;
import static com.android.server.wifi.WifiController.CMD_SET_AP;
import static com.android.server.wifi.WifiController.CMD_USER_PRESENT;
import static com.android.server.wifi.WifiController.CMD_WIFI_TOGGLED;
import android.net.wifi.HotspotClient;
import android.net.wifi.PPPOEInfo;

import com.mediatek.common.wifi.IWifiFwkExt;
import com.mediatek.server.wifi.WifiNvRamAgent;
import com.mediatek.xlog.SXlog;
import android.net.wifi.WpsInfo;
import android.os.ServiceManager;
import android.text.TextUtils;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Comparator;
import android.os.INetworkManagementService;



/// M: [Mobile Managerment]
import com.mediatek.common.mom.MobileManagerUtils;
import com.mediatek.common.mom.SubPermissions;
import com.mediatek.common.mom.IRequestedPermissionCallback;




/**
 * WifiService handles remote WiFi operation requests by implementing
 * the IWifiManager interface.
 *
 * @hide
 */
public final class WifiServiceImpl extends IWifiManager.Stub {
    private static final String TAG = "WifiService";
    private static final boolean DBG = true;

    final WifiStateMachine mWifiStateMachine;

    private final Context mContext;

    final LockList mLocks = new LockList();
    // some wifi lock statistics
    private int mFullHighPerfLocksAcquired;
    private int mFullHighPerfLocksReleased;
    private int mFullLocksAcquired;
    private int mFullLocksReleased;
    private int mScanLocksAcquired;
    private int mScanLocksReleased;

    private final List<Multicaster> mMulticasters =
            new ArrayList<Multicaster>();
    private int mMulticastEnabled;
    private int mMulticastDisabled;

    private final IBatteryStats mBatteryStats;
    private final AppOpsManager mAppOps;

    private String mInterfaceName;

    // Debug counter tracking scan requests sent by WifiManager
    private int scanRequestCounter = 0;

    /* Tracks the open wi-fi network notification */
    private WifiNotificationController mNotificationController;
    /* Polls traffic stats and notifies clients */
    private WifiTrafficPoller mTrafficPoller;
    /* Tracks the persisted states for wi-fi & airplane mode */
    final WifiSettingsStore mSettingsStore;

    final boolean mBatchedScanSupported;

    /**
     * Asynchronous channel to WifiStateMachine
     */
    private AsyncChannel mWifiStateMachineChannel;

    /**
     * Handles client connections
     */
    private class ClientHandler extends Handler {

        ClientHandler(android.os.Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        if (DBG) Slog.d(TAG, "New client listening to asynchronous messages");
                        // We track the clients by the Messenger
                        // since it is expected to be always available
                        mTrafficPoller.addClient(msg.replyTo);
                    } else {
                        Slog.e(TAG, "Client connection failure, error=" + msg.arg1);
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    if (msg.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                        if (DBG) Slog.d(TAG, "Send failed, client connection lost");
                    } else {
                        if (DBG) Slog.d(TAG, "Client connection lost with reason: " + msg.arg1);
                    }
                    mTrafficPoller.removeClient(msg.replyTo);
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    AsyncChannel ac = new AsyncChannel();
                    ac.connect(mContext, this, msg.replyTo);
                    break;
                }
                /* Client commands are forwarded to state machine */
                case WifiManager.CONNECT_NETWORK:
                case WifiManager.SAVE_NETWORK: {
                    WifiConfiguration config = (WifiConfiguration) msg.obj;
                    int networkId = msg.arg1;
                    if (msg.what == WifiManager.SAVE_NETWORK) {
                        if (config != null) {
                            if (config.networkId == WifiConfiguration.INVALID_NETWORK_ID) {
                                config.creatorUid = Binder.getCallingUid();
                            } else {
                                config.lastUpdateUid = Binder.getCallingUid();
                            }
                        }
                        Slog.e("WiFiServiceImpl ", "SAVE"
                                + " nid=" + Integer.toString(networkId)
                                + " uid=" + Integer.toString(config.creatorUid)
                                + "/" + Integer.toString(config.lastUpdateUid));
                    }
                    if (msg.what == WifiManager.CONNECT_NETWORK) {
                        if (config != null) {
                            if (config.networkId == WifiConfiguration.INVALID_NETWORK_ID) {
                                config.creatorUid = Binder.getCallingUid();
                            } else {
                                config.lastUpdateUid = Binder.getCallingUid();
                            }
                        }
                        Slog.e("WiFiServiceImpl ", "CONNECT "
                                + " nid=" + Integer.toString(networkId)
                                + " uid=" + Binder.getCallingUid());
                    }
                    if (config != null && config.isValid()) {
                        if (DBG) Slog.d(TAG, "Connect with config" + config);
                        mWifiStateMachine.sendMessage(Message.obtain(msg));
                    } else if (config == null
                            && networkId != WifiConfiguration.INVALID_NETWORK_ID) {
                        if (DBG) Slog.d(TAG, "Connect with networkId" + networkId);
                        mWifiStateMachine.sendMessage(Message.obtain(msg));
                    } else {
                        Slog.e(TAG, "ClientHandler.handleMessage ignoring invalid msg=" + msg);
                        if (msg.what == WifiManager.CONNECT_NETWORK) {
                            replyFailed(msg, WifiManager.CONNECT_NETWORK_FAILED,
                                    WifiManager.INVALID_ARGS);
                        } else {
                            replyFailed(msg, WifiManager.SAVE_NETWORK_FAILED,
                                    WifiManager.INVALID_ARGS);
                        }
                    }
                    break;
                }
                case WifiManager.FORGET_NETWORK:
                    if (isOwner(msg.sendingUid)) {
                        mWifiStateMachine.sendMessage(Message.obtain(msg));
                    } else {
                        Slog.e(TAG, "Forget is not authorized for user");
                        replyFailed(msg, WifiManager.FORGET_NETWORK_FAILED,
                                WifiManager.NOT_AUTHORIZED);
                    }
                    break;
                case WifiManager.START_WPS:
                case WifiManager.CANCEL_WPS:
                case WifiManager.DISABLE_NETWORK:
                case WifiManager.RSSI_PKTCNT_FETCH:
                ///M:
                case WifiManager.START_PPPOE:
                case WifiManager.STOP_PPPOE:
                /** M: NFC Float II @{ */
                case WifiManager.START_WPS_REG:
                case WifiManager.START_WPS_ER:
                case WifiManager.GET_WPS_PIN_AND_CONNECT:
                case WifiManager.GET_WPS_CRED_AND_CONNECT:
                case WifiManager.WRITE_PIN_TO_NFC:
                case WifiManager.WRITE_CRED_TO_NFC:
                case WifiManager.GET_PIN_FROM_NFC:
                case WifiManager.GET_CRED_FROM_NFC:
                /** @} */
                //M: for proprietary use, not reconnect or scan during a period time
                case WifiManager.SET_WIFI_NOT_RECONNECT_AND_SCAN: {
                    mWifiStateMachine.sendMessage(Message.obtain(msg));
                    break;
                }
                default: {
                    Slog.d(TAG, "ClientHandler.handleMessage ignoring msg=" + msg);
                    break;
                }
            }
        }

        private void replyFailed(Message msg, int what, int why) {
            Message reply = msg.obtain();
            reply.what = what;
            reply.arg1 = why;
            try {
                msg.replyTo.send(reply);
            } catch (RemoteException e) {
                // There's not much we can do if reply can't be sent!
            }
        }
    }
    private ClientHandler mClientHandler;

    /**
     * Handles interaction with WifiStateMachine
     */
    private class WifiStateMachineHandler extends Handler {
        private AsyncChannel mWsmChannel;

        WifiStateMachineHandler(android.os.Looper looper) {
            super(looper);
            mWsmChannel = new AsyncChannel();
            mWsmChannel.connect(mContext, this, mWifiStateMachine.getHandler());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        mWifiStateMachineChannel = mWsmChannel;
                    } else {
                        Slog.e(TAG, "WifiStateMachine connection failure, error=" + msg.arg1);
                        mWifiStateMachineChannel = null;
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    Slog.e(TAG, "WifiStateMachine channel lost, msg.arg1 =" + msg.arg1);
                    mWifiStateMachineChannel = null;
                    //Re-establish connection to state machine
                    mWsmChannel.connect(mContext, this, mWifiStateMachine.getHandler());
                    break;
                }
                default: {
                    Slog.d(TAG, "WifiStateMachineHandler.handleMessage ignoring msg=" + msg);
                    break;
                }
            }
        }
    }

    WifiStateMachineHandler mWifiStateMachineHandler;

    private WifiWatchdogStateMachine mWifiWatchdogStateMachine;

    private WifiController mWifiController;

///M: MTK add parameter
    // M: For bug fix
    private boolean mWifiIpoOff = false;
    private boolean mIsReceiverRegistered = false;

    private static final String LEASES_FILE = "/data/misc/dhcp/dnsmasq.leases";

    ///M: for alarm-boot feature
    private static final String NORMAL_BOOT_ACTION = "android.intent.action.normal.boot";

    public WifiServiceImpl(Context context) {
        mContext = context;

        mInterfaceName =  SystemProperties.get("wifi.interface", "wlan0");

        mTrafficPoller = new WifiTrafficPoller(mContext, mInterfaceName);
        mWifiStateMachine = new WifiStateMachine(mContext, mInterfaceName, mTrafficPoller);
        mWifiStateMachine.enableRssiPolling(true);
        mBatteryStats = BatteryStatsService.getService();
        mAppOps = (AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);

        mNotificationController = new WifiNotificationController(mContext, mWifiStateMachine);
        mSettingsStore = new WifiSettingsStore(mContext);

        HandlerThread wifiThread = new HandlerThread("WifiService");
        wifiThread.start();
        mClientHandler = new ClientHandler(wifiThread.getLooper());
        mWifiStateMachineHandler = new WifiStateMachineHandler(wifiThread.getLooper());
        mWifiController = new WifiController(mContext, this, wifiThread.getLooper());

        mBatchedScanSupported = mContext.getResources().getBoolean(
                R.bool.config_wifi_batched_scan_supported);

        ///M:
        initializeExtra();
    }


    /**
     * Check if Wi-Fi needs to be enabled and start
     * if needed
     *
     * This function is used only at boot time
     */
    public void checkAndStartWifi() {

      ///M: @{
      mWifiStateMachine.autoConnectInit();
      mWifiController.start();
      ///@}

     ///M: alarm-boot feature@{
        String bootReason = SystemProperties.get("sys.boot.reason");
        boolean isAlarmBoot = (bootReason != null && bootReason.equals("1")) ? true : false;

        if (isAlarmBoot) {
            SXlog.i(TAG, "isAlarmBoot =true don't start wifi");
            //register broadcast receiver
            mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        SXlog.i(TAG, "receive NORMAL_BOOT_ACTION for alarm boot");
                        mContext.unregisterReceiver(this);
                        checkAndStartWifi();
                    }
                }, new IntentFilter(NORMAL_BOOT_ACTION));
            return;
        }
        ///@}


        /* Check if wi-fi needs to be enabled */
        boolean wifiEnabled = mSettingsStore.isWifiToggleEnabled();
        Slog.i(TAG, "WifiService starting up with Wi-Fi " +
                (wifiEnabled ? "enabled" : "disabled"));

        registerForScanModeChange();
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        ///M: modify for timing issue to access Settings.Global.AIRPLANE_MODE_ON
                        boolean isAirplaneModeOn = intent.getBooleanExtra("state", false);
                        SXlog.i(TAG, "ACTION_AIRPLANE_MODE_CHANGED isAirplaneModeOn=" + isAirplaneModeOn);

                        if (mSettingsStore.handleAirplaneModeToggled(isAirplaneModeOn)) {
                            mWifiController.sendMessage(CMD_AIRPLANE_TOGGLED);
                        }
                    }
                },
                new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED));

        // Adding optimizations of only receiving broadcasts when wifi is enabled
        // can result in race conditions when apps toggle wifi in the background
        // without active user involvement. Always receive broadcasts.
        registerForBroadcasts();


        // If we are already disabled (could be due to airplane mode), avoid changing persist
        // state here
        if (wifiEnabled) setWifiEnabled(wifiEnabled);

        mWifiWatchdogStateMachine = WifiWatchdogStateMachine.
               makeWifiWatchdogStateMachine(mContext, mWifiStateMachine.getMessenger());
    }

    /**
     * see {@link android.net.wifi.WifiManager#pingSupplicant()}
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean pingSupplicant() {
        enforceAccessPermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncPingSupplicant(mWifiStateMachineChannel);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return false;
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#getChannelList}
     */
    public List<WifiChannel> getChannelList() {
        enforceAccessPermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncGetChannelList(mWifiStateMachineChannel);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return null;
        }
    }

    // Start a location scan.
    // L release: A location scan is implemented as a normal scan and avoids scanning DFS channels
    public void startLocationRestrictedScan(WorkSource workSource) {
        enforceChangePermission();
        enforceLocationHardwarePermission();
        List<WifiChannel> channels = getChannelList();
        if (channels == null) {
            Slog.e(TAG, "startLocationRestrictedScan cant get channels");
            return;
        }
        ScanSettings settings = new ScanSettings();
        for (WifiChannel channel : channels) {
            if (!channel.isDFS) {
                settings.channelSet.add(channel);
            }
        }
        if (workSource == null) {
            // Make sure we always have a workSource indicating the origin of the scan
            // hence if there is none, pick an internal WifiStateMachine one
            workSource = new WorkSource(WifiStateMachine.DFS_RESTRICTED_SCAN_REQUEST);
        }
        startScan(settings, workSource);
    }

    /**
     * see {@link android.net.wifi.WifiManager#startScan}
     * and {@link android.net.wifi.WifiManager#startCustomizedScan}
     *
     * @param settings If null, use default parameter, i.e. full scan.
     * @param workSource If null, all blame is given to the calling uid.
     */
    public void startScan(ScanSettings settings, WorkSource workSource) {
        enforceChangePermission();
        if (settings != null) {
            settings = new ScanSettings(settings);
            if (!settings.isValid()) {
                Slog.e(TAG, "invalid scan setting");
                return;
            }
        }
        if (workSource != null) {
            enforceWorkSourcePermission();
            // WifiManager currently doesn't use names, so need to clear names out of the
            // supplied WorkSource to allow future WorkSource combining.
            workSource.clearNames();
        }
        mWifiStateMachine.startScan(Binder.getCallingUid(), scanRequestCounter++,
                settings, workSource);
    }

    private class BatchedScanRequest extends DeathRecipient {
        final BatchedScanSettings settings;
        final int uid;
        final int pid;
        final WorkSource workSource;

        BatchedScanRequest(BatchedScanSettings settings, IBinder binder, WorkSource ws) {
            super(0, null, binder, null);
            this.settings = settings;
            this.uid = getCallingUid();
            this.pid = getCallingPid();
            workSource = ws;
        }
        public void binderDied() {
            stopBatchedScan(settings, uid, pid);
        }
        public String toString() {
            return "BatchedScanRequest{settings=" + settings + ", binder=" + mBinder + "}";
        }

        public boolean isSameApp(int uid, int pid) {
            return (this.uid == uid && this.pid == pid);
        }
    }

    private final List<BatchedScanRequest> mBatchedScanners = new ArrayList<BatchedScanRequest>();

    public boolean isBatchedScanSupported() {
        return mBatchedScanSupported;
    }

    public void pollBatchedScan() {
        enforceChangePermission();
        if (mBatchedScanSupported == false) return;
        mWifiStateMachine.requestBatchedScanPoll();
    }

    public String getWpsNfcConfigurationToken(int netId) {
        enforceConnectivityInternalPermission();
        return mWifiStateMachine.syncGetWpsNfcConfigurationToken(netId);
    }

    /**
     * see {@link android.net.wifi.WifiManager#requestBatchedScan()}
     */
    public boolean requestBatchedScan(BatchedScanSettings requested, IBinder binder,
            WorkSource workSource) {
        enforceChangePermission();
        if (workSource != null) {
            enforceWorkSourcePermission();
            // WifiManager currently doesn't use names, so need to clear names out of the
            // supplied WorkSource to allow future WorkSource combining.
            workSource.clearNames();
        }
        if (mBatchedScanSupported == false) return false;
        requested = new BatchedScanSettings(requested);
        if (requested.isInvalid()) return false;
        BatchedScanRequest r = new BatchedScanRequest(requested, binder, workSource);
        synchronized(mBatchedScanners) {
            mBatchedScanners.add(r);
            resolveBatchedScannersLocked();
        }
        return true;
    }

    public List<BatchedScanResult> getBatchedScanResults(String callingPackage) {
        enforceAccessPermission();
        if (mBatchedScanSupported == false) return new ArrayList<BatchedScanResult>();
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        boolean hasInteractUsersFull = checkInteractAcrossUsersFull();
        long ident = Binder.clearCallingIdentity();
        try {
            if (mAppOps.noteOp(AppOpsManager.OP_WIFI_SCAN, uid, callingPackage)
                    != AppOpsManager.MODE_ALLOWED) {
                return new ArrayList<BatchedScanResult>();
            }
            if (!isCurrentProfile(userId) && !hasInteractUsersFull) {
                return new ArrayList<BatchedScanResult>();
            }
            return mWifiStateMachine.syncGetBatchedScanResultsList();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void stopBatchedScan(BatchedScanSettings settings) {
        enforceChangePermission();
        if (mBatchedScanSupported == false) return;
        stopBatchedScan(settings, getCallingUid(), getCallingPid());
    }

    private void stopBatchedScan(BatchedScanSettings settings, int uid, int pid) {
        ArrayList<BatchedScanRequest> found = new ArrayList<BatchedScanRequest>();
        synchronized(mBatchedScanners) {
            for (BatchedScanRequest r : mBatchedScanners) {
                if (r.isSameApp(uid, pid) && (settings == null || settings.equals(r.settings))) {
                    found.add(r);
                    if (settings != null) break;
                }
            }
            for (BatchedScanRequest r : found) {
                mBatchedScanners.remove(r);
            }
            if (found.size() != 0) {
                resolveBatchedScannersLocked();
            }
        }
    }

    private void resolveBatchedScannersLocked() {
        BatchedScanSettings setting = new BatchedScanSettings();
        WorkSource responsibleWorkSource = null;
        int responsibleUid = 0;
        double responsibleCsph = 0; // Channel Scans Per Hour

        if (mBatchedScanners.size() == 0) {
            mWifiStateMachine.setBatchedScanSettings(null, 0, 0, null);
            return;
        }
        for (BatchedScanRequest r : mBatchedScanners) {
            BatchedScanSettings s = r.settings;

            // evaluate responsibility
            int currentChannelCount;
            int currentScanInterval;
            double currentCsph;

            if (s.channelSet == null || s.channelSet.isEmpty()) {
                // all channels - 11 B and 9 A channels roughly.
                currentChannelCount = 9 + 11;
            } else {
                currentChannelCount = s.channelSet.size();
                // these are rough est - no real need to correct for reg-domain;
                if (s.channelSet.contains("A")) currentChannelCount += (9 - 1);
                if (s.channelSet.contains("B")) currentChannelCount += (11 - 1);

            }
            ///M: avoid devide by zero
            if (s.scanIntervalSec == BatchedScanSettings.UNSPECIFIED || s.scanIntervalSec <= 0) {
                currentScanInterval = BatchedScanSettings.DEFAULT_INTERVAL_SEC;
            } else {
                currentScanInterval = s.scanIntervalSec;
            }
            currentCsph = 60 * 60 * currentChannelCount / currentScanInterval;

            if (currentCsph > responsibleCsph) {
                responsibleUid = r.uid;
                responsibleWorkSource = r.workSource;
                responsibleCsph = currentCsph;
            }

            if (s.maxScansPerBatch != BatchedScanSettings.UNSPECIFIED &&
                    s.maxScansPerBatch < setting.maxScansPerBatch) {
                setting.maxScansPerBatch = s.maxScansPerBatch;
            }
            if (s.maxApPerScan != BatchedScanSettings.UNSPECIFIED &&
                    (setting.maxApPerScan == BatchedScanSettings.UNSPECIFIED ||
                    s.maxApPerScan > setting.maxApPerScan)) {
                setting.maxApPerScan = s.maxApPerScan;
            }
            if (s.scanIntervalSec != BatchedScanSettings.UNSPECIFIED &&
                    s.scanIntervalSec < setting.scanIntervalSec) {
                setting.scanIntervalSec = s.scanIntervalSec;
            }
            if (s.maxApForDistance != BatchedScanSettings.UNSPECIFIED &&
                    (setting.maxApForDistance == BatchedScanSettings.UNSPECIFIED ||
                    s.maxApForDistance > setting.maxApForDistance)) {
                setting.maxApForDistance = s.maxApForDistance;
            }
            if (s.channelSet != null && s.channelSet.size() != 0) {
                if (setting.channelSet == null || setting.channelSet.size() != 0) {
                    if (setting.channelSet == null) setting.channelSet = new ArrayList<String>();
                    for (String i : s.channelSet) {
                        if (setting.channelSet.contains(i) == false) setting.channelSet.add(i);
                    }
                } // else, ignore the constraint - we already use all channels
            } else {
                if (setting.channelSet == null || setting.channelSet.size() != 0) {
                    setting.channelSet = new ArrayList<String>();
                }
            }
        }

        setting.constrain();
        mWifiStateMachine.setBatchedScanSettings(setting, responsibleUid, (int)responsibleCsph,
                responsibleWorkSource);
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE,
                                                "WifiService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                                                "WifiService");
    }

    private void enforceLocationHardwarePermission() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE,
                "LocationHardware");
    }

    private void enforceReadCredentialPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.READ_WIFI_CREDENTIAL,
                                "WifiService");
    }

    private void enforceWorkSourcePermission() {
        mContext.enforceCallingPermission(android.Manifest.permission.UPDATE_DEVICE_STATS,
                                                "WifiService");

    }

    private void enforceMulticastChangePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                "WifiService");
    }

    private void enforceConnectivityInternalPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "ConnectivityService");
    }

    /**
     * see {@link android.net.wifi.WifiManager#setWifiEnabled(boolean)}
     * @param enable {@code true} to enable, {@code false} to disable.
     * @return {@code true} if the enable/disable operation was
     *         started or is already in the queue.
     */
    public synchronized boolean setWifiEnabled(boolean enable) {
        enforceChangePermission();
        Slog.d(TAG, "setWifiEnabled: " + enable + " pid=" + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());

        if (MobileManagerUtils.isSupported()) {
           if (SystemProperties.get("ro.mtk_mobile_management").equals("1")) {
               if (enable == true) {

                   MobileManagerUtils.checkPermissionAsync(SubPermissions.CHANGE_WIFI_STATE_ON,
                           Binder.getCallingUid(), new wifiOnPermissionCheckCallback());
                   return true;
               }
           }
        }

        /*
        * Caller might not have WRITE_SECURE_SETTINGS,
        * only CHANGE_WIFI_STATE is enforced
        */

        long ident = Binder.clearCallingIdentity();
        try {
            if (! mSettingsStore.handleWifiToggled(enable)) {
                // Nothing to do if wifi cannot be toggled
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        ///M: put extra mWifiIpoOff
        mWifiController.obtainMessage(CMD_WIFI_TOGGLED, mWifiIpoOff ? 1 : 0).sendToTarget();
        return true;
    }

    ///* M: For IPO if enable == true meansnormal state, otherwise means  IPO shutdown
    public synchronized boolean setWifiEnabledForIPO(boolean enable) {
        SXlog.d(TAG, "setWifiEnabledForIPO:" + enable + ", pid:" + Binder.getCallingPid()
                + ", uid:" + Binder.getCallingUid());
        enforceChangePermission();
        if (enable) {
            mWifiIpoOff = false;
        } else {
            mWifiIpoOff = true;
            // clear setCheckSavedStateAtBoot, so next boot will process saved state
            mSettingsStore.setCheckSavedStateAtBoot(false);
        }

        //M: after this line, go to normal state, wifi state depend on wifiSettingStore

        ///M: put extra mWifiIpoOff
        mWifiController.obtainMessage(CMD_WIFI_TOGGLED, mWifiIpoOff ? 1 : 0).sendToTarget();

        if (enable) {
            if (!mIsReceiverRegistered) {
                registerForBroadcasts();
                mIsReceiverRegistered = true;
            }
        } else if (mIsReceiverRegistered) {
            mContext.unregisterReceiver(mReceiver);
            mIsReceiverRegistered = false;
        }

        return true;
    }


    /**
     * see {@link WifiManager#getWifiState()}
     * @return One of {@link WifiManager#WIFI_STATE_DISABLED},
     *         {@link WifiManager#WIFI_STATE_DISABLING},
     *         {@link WifiManager#WIFI_STATE_ENABLED},
     *         {@link WifiManager#WIFI_STATE_ENABLING},
     *         {@link WifiManager#WIFI_STATE_UNKNOWN}
     */
    public int getWifiEnabledState() {
        enforceAccessPermission();
        return mWifiStateMachine.syncGetWifiState();
    }

    /**
     * see {@link android.net.wifi.WifiManager#setWifiApEnabled(WifiConfiguration, boolean)}
     * @param wifiConfig SSID, security and channel details as
     *        part of WifiConfiguration
     * @param enabled true to enable and false to disable
     */
    public void setWifiApEnabled(WifiConfiguration wifiConfig, boolean enabled) {
        SXlog.d(TAG, "setWifiApEnabled:" + enabled + ", pid:" + Binder.getCallingPid()
            + ", uid:" + Binder.getCallingUid() + ", wifiConfig:" + wifiConfig);
        enforceChangePermission();
        ConnectivityManager.enforceTetherChangePermission(mContext);
        UserManager um = UserManager.get(mContext);
        if (um.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING)) {
            throw new SecurityException("DISALLOW_CONFIG_TETHERING is enabled for this user.");
        }
        // null wifiConfig is a meaningful input for CMD_SET_AP
        if (wifiConfig == null || wifiConfig.isValid()) {
            mWifiController.obtainMessage(CMD_SET_AP, enabled ? 1 : 0, 0, wifiConfig).sendToTarget();
        } else {
            Slog.e(TAG, "Invalid WifiConfiguration");
        }
    }

    /**
     * see {@link WifiManager#getWifiApState()}
     * @return One of {@link WifiManager#WIFI_AP_STATE_DISABLED},
     *         {@link WifiManager#WIFI_AP_STATE_DISABLING},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLED},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLING},
     *         {@link WifiManager#WIFI_AP_STATE_FAILED}
     */
    public int getWifiApEnabledState() {
        enforceAccessPermission();
        return mWifiStateMachine.syncGetWifiApState();
    }

    /**
     * see {@link WifiManager#getWifiApConfiguration()}
     * @return soft access point configuration
     */
    public WifiConfiguration getWifiApConfiguration() {
        enforceAccessPermission();
        return mWifiStateMachine.syncGetWifiApConfiguration();
    }

    /**
     * see {@link WifiManager#setWifiApConfiguration(WifiConfiguration)}
     * @param wifiConfig WifiConfiguration details for soft access point
     */
    public void setWifiApConfiguration(WifiConfiguration wifiConfig) {
        enforceChangePermission();
        if (wifiConfig == null)
            return;
        if (wifiConfig.isValid()) {
            mWifiStateMachine.setWifiApConfiguration(wifiConfig);
        } else {
            Slog.e(TAG, "Invalid WifiConfiguration");
        }
    }

    /**
     * @param enable {@code true} to enable, {@code false} to disable.
     * @return {@code true} if the enable/disable operation was
     *         started or is already in the queue.
     */
    public boolean isScanAlwaysAvailable() {
        enforceAccessPermission();
        return mSettingsStore.isScanAlwaysAvailable();
    }

    /**
     * see {@link android.net.wifi.WifiManager#disconnect()}
     */
    public void disconnect() {
        SXlog.d(TAG, "disconnect, pid:" + Binder.getCallingPid()
            + ", uid:" + Binder.getCallingUid());
        enforceChangePermission();
        mWifiStateMachine.disconnectCommand();
    }

    /**
     * see {@link android.net.wifi.WifiManager#reconnect()}
     */
    public void reconnect() {
        SXlog.d(TAG, "reconnect, pid:" + Binder.getCallingPid()
                + ", uid:" + Binder.getCallingUid());
        enforceChangePermission();
        mWifiStateMachine.reconnectCommand();
    }

    /**
     * see {@link android.net.wifi.WifiManager#reassociate()}
     */
    public void reassociate() {
        SXlog.d(TAG, "reassociate, pid:" + Binder.getCallingPid()
                + ", uid:" + Binder.getCallingUid());
        enforceChangePermission();
        mWifiStateMachine.reassociateCommand();
    }

    /**
     * see {@link android.net.wifi.WifiManager#getSupportedFeatures}
     */
    public int getSupportedFeatures() {
        enforceAccessPermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncGetSupportedFeatures(mWifiStateMachineChannel);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return 0;
        }
    }

    /**
     * see {@link android.net.wifi.WifiAdapter#reportActivityInfo}
     */
    public WifiActivityEnergyInfo reportActivityInfo() {
        enforceAccessPermission();
        WifiLinkLayerStats stats;
        WifiActivityEnergyInfo energyInfo = null;
        if (mWifiStateMachineChannel != null) {
            stats = mWifiStateMachine.syncGetLinkLayerStats(mWifiStateMachineChannel);
            if (stats != null) {
                // Convert the LinkLayerStats into EnergyActivity
                energyInfo = new WifiActivityEnergyInfo(
                        WifiActivityEnergyInfo.STACK_STATE_STATE_IDLE, stats.tx_time,
                        stats.rx_time, stats.on_time - stats.tx_time - stats.rx_time,
                        0 /* TBD */);
            }
            return energyInfo;
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return null;
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#getConfiguredNetworks()}
     * @return the list of configured networks
     */
    public List<WifiConfiguration> getConfiguredNetworks() {
        enforceAccessPermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncGetConfiguredNetworks(Binder.getCallingUid(),
                    mWifiStateMachineChannel);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return null;
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#getPrivilegedConfiguredNetworks()}
     * @return the list of configured networks with real preSharedKey
     */
    public List<WifiConfiguration> getPrivilegedConfiguredNetworks() {
        enforceReadCredentialPermission();
        enforceAccessPermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncGetPrivilegedConfiguredNetwork(mWifiStateMachineChannel);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return null;
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#addOrUpdateNetwork(WifiConfiguration)}
     * @return the supplicant-assigned identifier for the new or updated
     * network if the operation succeeds, or {@code -1} if it fails
     */
    public int addOrUpdateNetwork(WifiConfiguration config) {
        SXlog.d(TAG, "addOrUpdateNetwork, pid:" + Binder.getCallingPid()
                + ", uid:" + Binder.getCallingUid() + ", config:" + config);
        enforceChangePermission();
        if (config.isValid()) {
            //TODO: pass the Uid the WifiStateMachine as a message parameter
            Slog.e("addOrUpdateNetwork", " uid = " + Integer.toString(Binder.getCallingUid())
                    + " SSID " + config.SSID
                    + " nid=" + Integer.toString(config.networkId));
            if (config.networkId == WifiConfiguration.INVALID_NETWORK_ID) {
                config.creatorUid = Binder.getCallingUid();
            } else {
                config.lastUpdateUid = Binder.getCallingUid();
            }
            if (mWifiStateMachineChannel != null) {
                return mWifiStateMachine.syncAddOrUpdateNetwork(mWifiStateMachineChannel, config);
            } else {
                Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
                return -1;
            }
        } else {
            Slog.e(TAG, "bad network configuration");
            return -1;
        }
    }

     /**
     * See {@link android.net.wifi.WifiManager#removeNetwork(int)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @return {@code true} if the operation succeeded
     */
    public boolean removeNetwork(int netId) {
        SXlog.d(TAG, "removeNetwork, pid:" + Binder.getCallingPid()
                + ", uid:" + Binder.getCallingUid() + ", netId:" + netId);
        enforceChangePermission();

        if (!isOwner(Binder.getCallingUid())) {
            Slog.e(TAG, "Remove is not authorized for user");
            return false;
        }

        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncRemoveNetwork(mWifiStateMachineChannel, netId);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return false;
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#enableNetwork(int, boolean)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @param disableOthers if true, disable all other networks.
     * @return {@code true} if the operation succeeded
     */
    public boolean enableNetwork(int netId, boolean disableOthers) {
        SXlog.d(TAG, "enableNetwork, pid:" + Binder.getCallingPid() + ", uid:" + Binder.getCallingUid()
                        + ", netId:" + netId + ", disableOthers:" + disableOthers);
        enforceChangePermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncEnableNetwork(mWifiStateMachineChannel, netId,
                    disableOthers);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return false;
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#disableNetwork(int)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @return {@code true} if the operation succeeded
     */
    public boolean disableNetwork(int netId) {
        SXlog.d(TAG, "disableNetwork, pid:" + Binder.getCallingPid() + ", uid:" + Binder.getCallingUid()
                + ", netId:" + netId);
        enforceChangePermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncDisableNetwork(mWifiStateMachineChannel, netId);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return false;
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#getConnectionInfo()}
     * @return the Wi-Fi information, contained in {@link WifiInfo}.
     */
    public WifiInfo getConnectionInfo() {
        enforceAccessPermission();
        /*
         * Make sure we have the latest information, by sending
         * a status request to the supplicant.
         */
        ///M: @{
        if (mWifiStateMachineChannel != null) {
            mWifiStateMachine.syncUpdateRssi(mWifiStateMachineChannel);
        } else {
            SXlog.e(TAG, "mWifiStateMachineChannel is not initialized!");
        }
        ///@}

        return mWifiStateMachine.syncRequestConnectionInfo();
    }

    /**
     * Return the results of the most recent access point scan, in the form of
     * a list of {@link ScanResult} objects.
     * @return the list of results
     */
    public List<ScanResult> getScanResults(String callingPackage) {
        enforceAccessPermission();
        int userId = UserHandle.getCallingUserId();
        int uid = Binder.getCallingUid();
        boolean hasInteractUsersFull = checkInteractAcrossUsersFull();
        long ident = Binder.clearCallingIdentity();
        try {
            if (mAppOps.noteOp(AppOpsManager.OP_WIFI_SCAN, uid, callingPackage)
                    != AppOpsManager.MODE_ALLOWED) {
                return new ArrayList<ScanResult>();
            }
            if (!isCurrentProfile(userId) && !hasInteractUsersFull) {
                return new ArrayList<ScanResult>();
            }
            return mWifiStateMachine.syncGetScanResultsList();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Returns true if the caller holds INTERACT_ACROSS_USERS_FULL.
     */
    private boolean checkInteractAcrossUsersFull() {
        return mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the calling user is the current one or a profile of the
     * current user..
     */
    private boolean isCurrentProfile(int userId) {
        int currentUser = ActivityManager.getCurrentUser();
        if (userId == currentUser) {
            return true;
        }
        List<UserInfo> profiles = UserManager.get(mContext).getProfiles(currentUser);
        for (UserInfo user : profiles) {
            if (userId == user.id) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if uid is an application running under the owner or a profile of the owner.
     *
     * Note: Should not be called if identity is cleared.
     */
    private boolean isOwner(int uid) {
        long ident = Binder.clearCallingIdentity();
        int userId = UserHandle.getUserId(uid);
        try {
            int ownerUser = UserHandle.USER_OWNER;
            if (userId == ownerUser) {
                return true;
            }
            List<UserInfo> profiles = UserManager.get(mContext).getProfiles(ownerUser);
            for (UserInfo profile : profiles) {
                 SXlog.d(TAG, "profile" + profile.id);
                if (userId == profile.id) {
                    return true;
                }
            }
            return false;
        }
        finally {
            Binder.restoreCallingIdentity(ident);
        }
    }


    /**
     * Tell the supplicant to persist the current list of configured networks.
     * @return {@code true} if the operation succeeded
     *
     * TODO: deprecate this
     */
    public boolean saveConfiguration() {
        SXlog.d(TAG, "saveConfiguration, pid:" + Binder.getCallingPid()
                + ", uid:" + Binder.getCallingUid());
        boolean result = true;
        enforceChangePermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncSaveConfig(mWifiStateMachineChannel);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return false;
        }
    }

    /**
     * Set the country code
     * @param countryCode ISO 3166 country code.
     * @param persist {@code true} if the setting should be remembered.
     *
     * The persist behavior exists so that wifi can fall back to the last
     * persisted country code on a restart, when the locale information is
     * not available from telephony.
     */
    public void setCountryCode(String countryCode, boolean persist) {
        Slog.i(TAG, "WifiService trying to set country code to " + countryCode +
                " with persist set to " + persist);
        enforceConnectivityInternalPermission();
        final long token = Binder.clearCallingIdentity();
        try {
            mWifiStateMachine.setCountryCode(countryCode, persist);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Set the operational frequency band
     * @param band One of
     *     {@link WifiManager#WIFI_FREQUENCY_BAND_AUTO},
     *     {@link WifiManager#WIFI_FREQUENCY_BAND_5GHZ},
     *     {@link WifiManager#WIFI_FREQUENCY_BAND_2GHZ},
     * @param persist {@code true} if the setting should be remembered.
     *
     */
    public void setFrequencyBand(int band, boolean persist) {
        enforceChangePermission();
        if (!isDualBandSupported()) return;
        Slog.i(TAG, "WifiService trying to set frequency band to " + band +
                " with persist set to " + persist);
        final long token = Binder.clearCallingIdentity();
        try {
            mWifiStateMachine.setFrequencyBand(band, persist);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }


    /**
     * Get the operational frequency band
     */
    public int getFrequencyBand() {
        enforceAccessPermission();
        return mWifiStateMachine.getFrequencyBand();
    }

    public boolean isDualBandSupported() {
        //TODO: Should move towards adding a driver API that checks at runtime
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_wifi_dual_band_support);
    }

    /**
     * Return the DHCP-assigned addresses from the last successful DHCP request,
     * if any.
     * @return the DHCP information
     * @deprecated
     */
    public DhcpInfo getDhcpInfo() {
        enforceAccessPermission();
        DhcpResults dhcpResults = mWifiStateMachine.syncGetDhcpResults();

        DhcpInfo info = new DhcpInfo();

        if (dhcpResults.ipAddress != null &&
                dhcpResults.ipAddress.getAddress() instanceof Inet4Address) {
            info.ipAddress = NetworkUtils.inetAddressToInt((Inet4Address) dhcpResults.ipAddress.getAddress());
        }

        if (dhcpResults.gateway != null) {
            info.gateway = NetworkUtils.inetAddressToInt((Inet4Address) dhcpResults.gateway);
        }

        int dnsFound = 0;
        for (InetAddress dns : dhcpResults.dnsServers) {
            if (dns instanceof Inet4Address) {
                if (dnsFound == 0) {
                    info.dns1 = NetworkUtils.inetAddressToInt((Inet4Address)dns);
                } else {
                    info.dns2 = NetworkUtils.inetAddressToInt((Inet4Address)dns);
                }
                if (++dnsFound > 1) break;
            }
        }
        InetAddress serverAddress = dhcpResults.serverAddress;
        if (serverAddress instanceof Inet4Address) {
            info.serverAddress = NetworkUtils.inetAddressToInt((Inet4Address)serverAddress);
        }
        info.leaseDuration = dhcpResults.leaseDuration;

        return info;
    }

    /**
     * see {@link android.net.wifi.WifiManager#startWifi}
     *
     */
    public void startWifi() {
        enforceConnectivityInternalPermission();
        /* TODO: may be add permissions for access only to connectivity service
         * TODO: if a start issued, keep wifi alive until a stop issued irrespective
         * of WifiLock & device idle status unless wifi enabled status is toggled
         */
        ///M: modify@{
        if (mWifiStateMachine.shouldStartWifi()) {
            mWifiStateMachine.setDriverStart(true);
            mWifiStateMachine.reconnectCommand();
        } else {
            SXlog.d(TAG, "Shouldn't start wifi!");
        }
        ///@}
    }

    /**
     * see {@link android.net.wifi.WifiManager#stopWifi}
     *
     */
    public void stopWifi() {
        enforceConnectivityInternalPermission();
        /*
         * TODO: if a stop is issued, wifi is brought up only by startWifi
         * unless wifi enabled status is toggled
         */
        mWifiStateMachine.setDriverStart(false);
    }

    /**
     * see {@link android.net.wifi.WifiManager#addToBlacklist}
     *
     */
    public void addToBlacklist(String bssid) {
        enforceChangePermission();

        mWifiStateMachine.addToBlacklist(bssid);
    }

    /**
     * see {@link android.net.wifi.WifiManager#clearBlacklist}
     *
     */
    public void clearBlacklist() {
        enforceChangePermission();

        mWifiStateMachine.clearBlacklist();
    }

    /**
     * enable TDLS for the local NIC to remote NIC
     * The APPs don't know the remote MAC address to identify NIC though,
     * so we need to do additional work to find it from remote IP address
     */

    class TdlsTaskParams {
        public String remoteIpAddress;
        public boolean enable;
    }

    class TdlsTask extends AsyncTask<TdlsTaskParams, Integer, Integer> {
        @Override
        protected Integer doInBackground(TdlsTaskParams... params) {

            // Retrieve parameters for the call
            TdlsTaskParams param = params[0];
            String remoteIpAddress = param.remoteIpAddress.trim();
            boolean enable = param.enable;

            // Get MAC address of Remote IP
            String macAddress = null;

            BufferedReader reader = null;

            try {
                reader = new BufferedReader(new FileReader("/proc/net/arp"));

                // Skip over the line bearing colum titles
                String line = reader.readLine();

                while ((line = reader.readLine()) != null) {
                    String[] tokens = line.split("[ ]+");
                    if (tokens.length < 6) {
                        continue;
                    }

                    // ARP column format is
                    // Address HWType HWAddress Flags Mask IFace
                    String ip = tokens[0];
                    String mac = tokens[3];

                    if (remoteIpAddress.equals(ip)) {
                        macAddress = mac;
                        break;
                    }
                }

                if (macAddress == null) {
                    Slog.w(TAG, "Did not find remoteAddress {" + remoteIpAddress + "} in " +
                            "/proc/net/arp");
                } else {
                    enableTdlsWithMacAddress(macAddress, enable);
                }

            } catch (FileNotFoundException e) {
                Slog.e(TAG, "Could not open /proc/net/arp to lookup mac address");
            } catch (IOException e) {
                Slog.e(TAG, "Could not read /proc/net/arp to lookup mac address");
            } finally {
                try {
                    if (reader != null) {
                        reader.close();
                    }
                }
                catch (IOException e) {
                    // Do nothing
                }
            }

            return 0;
        }
    }

    public void enableTdls(String remoteAddress, boolean enable) {
        if (remoteAddress == null) {
          throw new IllegalArgumentException("remoteAddress cannot be null");
        }

        TdlsTaskParams params = new TdlsTaskParams();
        params.remoteIpAddress = remoteAddress;
        params.enable = enable;
        new TdlsTask().execute(params);
    }


    public void enableTdlsWithMacAddress(String remoteMacAddress, boolean enable) {
        if (remoteMacAddress == null) {
          throw new IllegalArgumentException("remoteMacAddress cannot be null");
        }

        mWifiStateMachine.enableTdls(remoteMacAddress, enable);
    }

    /**
     * Get a reference to handler. This is used by a client to establish
     * an AsyncChannel communication with WifiService
     */
    public Messenger getWifiServiceMessenger() {
        SXlog.d(TAG, "getWifiServiceMessenger, pid:" + Binder.getCallingPid()
                + ", uid:" + Binder.getCallingUid());
        enforceAccessPermission();
        enforceChangePermission();
        return new Messenger(mClientHandler);
    }

    /**
     * Disable an ephemeral network, i.e. network that is created thru a WiFi Scorer
     */
    public void disableEphemeralNetwork(String SSID) {
        enforceAccessPermission();
        enforceChangePermission();
        mWifiStateMachine.disableEphemeralNetwork(SSID);
    }

    /**
     * Get the IP and proxy configuration file
     */
    public String getConfigFile() {
        enforceAccessPermission();
        return mWifiStateMachine.getConfigFile();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            SXlog.d(TAG, "onReceive, action:" + action);
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mWifiController.sendMessage(CMD_SCREEN_ON);
            } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                mWifiController.sendMessage(CMD_USER_PRESENT);
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mWifiController.sendMessage(CMD_SCREEN_OFF);
            } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                int pluggedType = intent.getIntExtra("plugged", 0);
                mWifiController.sendMessage(CMD_BATTERY_CHANGED, pluggedType, 0, null);
            } else if (action.equals(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        BluetoothAdapter.STATE_DISCONNECTED);
                mWifiStateMachine.sendBluetoothAdapterStateChange(state);
            } else if (action.equals(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED)) {
                boolean emergencyMode = intent.getBooleanExtra("phoneinECMState", false);
                mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, emergencyMode ? 1 : 0, 0);
            }
        }
    };

    /**
     * Observes settings changes to scan always mode.
     */
    private void registerForScanModeChange() {
        ContentObserver contentObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                mSettingsStore.handleWifiScanAlwaysAvailableToggled();
                mWifiController.sendMessage(CMD_SCAN_ALWAYS_MODE_CHANGED);
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE),
                false, contentObserver);
    }

    private void registerForBroadcasts() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_USER_PRESENT);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        mContext.registerReceiver(mReceiver, intentFilter);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WifiService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        pw.println("Wi-Fi is " + mWifiStateMachine.syncGetWifiStateByName());
        pw.println("Stay-awake conditions: " +
                Settings.Global.getInt(mContext.getContentResolver(),
                                       Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0));
        pw.println("mMulticastEnabled " + mMulticastEnabled);
        pw.println("mMulticastDisabled " + mMulticastDisabled);
        mWifiController.dump(fd, pw, args);
        mSettingsStore.dump(fd, pw, args);
        mNotificationController.dump(fd, pw, args);
        mTrafficPoller.dump(fd, pw, args);

        pw.println("Latest scan results:");
        List<ScanResult> scanResults = mWifiStateMachine.syncGetScanResultsList();
        long nowMs = System.currentTimeMillis();
        if (scanResults != null && scanResults.size() != 0) {
            pw.println("    BSSID              Frequency  RSSI    Age      SSID " +
                    "                                Flags");
            for (ScanResult r : scanResults) {
                long ageSec = 0;
                long ageMilli = 0;
                if (nowMs > r.seen && r.seen > 0) {
                    ageSec = (nowMs - r.seen) / 1000;
                    ageMilli = (nowMs - r.seen) % 1000;
                }
                String candidate = " ";
                if (r.isAutoJoinCandidate > 0) candidate = "+";
                pw.printf("  %17s  %9d  %5d  %3d.%03d%s   %-32s  %s\n",
                                         r.BSSID,
                                         r.frequency,
                                         r.level,
                                         ageSec, ageMilli,
                                         candidate,
                                         r.SSID == null ? "" : r.SSID,
                                         r.capabilities);
            }
        }
        pw.println();
        pw.println("Locks acquired: " + mFullLocksAcquired + " full, " +
                mFullHighPerfLocksAcquired + " full high perf, " +
                mScanLocksAcquired + " scan");
        pw.println("Locks released: " + mFullLocksReleased + " full, " +
                mFullHighPerfLocksReleased + " full high perf, " +
                mScanLocksReleased + " scan");
        pw.println();
        pw.println("Locks held:");
        mLocks.dump(pw);

        mWifiWatchdogStateMachine.dump(fd, pw, args);
        pw.println();
        mWifiStateMachine.dump(fd, pw, args);
        pw.println();
    }

    private class WifiLock extends DeathRecipient {
        WifiLock(int lockMode, String tag, IBinder binder, WorkSource ws) {
            super(lockMode, tag, binder, ws);
        }

        public void binderDied() {
            synchronized (mLocks) {
                releaseWifiLockLocked(mBinder);
            }
        }

        public String toString() {
            return "WifiLock{" + mTag + " type=" + mMode + " binder=" + mBinder + "}";
        }
    }

    class LockList {
        private List<WifiLock> mList;

        private LockList() {
            mList = new ArrayList<WifiLock>();
        }

        synchronized boolean hasLocks() {
            return !mList.isEmpty();
        }

        synchronized int getStrongestLockMode() {
            if (mList.isEmpty()) {
                return WifiManager.WIFI_MODE_FULL;
            }

            if (mFullHighPerfLocksAcquired > mFullHighPerfLocksReleased) {
                return WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            }

            if (mFullLocksAcquired > mFullLocksReleased) {
                return WifiManager.WIFI_MODE_FULL;
            }

            return WifiManager.WIFI_MODE_SCAN_ONLY;
        }

        synchronized void updateWorkSource(WorkSource ws) {
            for (int i = 0; i < mLocks.mList.size(); i++) {
                ws.add(mLocks.mList.get(i).mWorkSource);
            }
        }

        private void addLock(WifiLock lock) {
            if (findLockByBinder(lock.mBinder) < 0) {
                mList.add(lock);
            }
        }

        private WifiLock removeLock(IBinder binder) {
            int index = findLockByBinder(binder);
            if (index >= 0) {
                WifiLock ret = mList.remove(index);
                ret.unlinkDeathRecipient();
                return ret;
            } else {
                return null;
            }
        }

        private int findLockByBinder(IBinder binder) {
            int size = mList.size();
            for (int i = size - 1; i >= 0; i--) {
                if (mList.get(i).mBinder == binder)
                    return i;
            }
            return -1;
        }

        private void dump(PrintWriter pw) {
            for (WifiLock l : mList) {
                pw.print("    ");
                pw.println(l);
            }
        }
    }

    void enforceWakeSourcePermission(int uid, int pid) {
        if (uid == android.os.Process.myUid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_DEVICE_STATS,
                pid, uid, null);
    }

    public boolean acquireWifiLock(IBinder binder, int lockMode, String tag, WorkSource ws) {
        SXlog.d(TAG, "acquireWifiLock, pid:" + Binder.getCallingPid()
                + ", uid:" + Binder.getCallingUid());
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        if (lockMode != WifiManager.WIFI_MODE_FULL &&
                lockMode != WifiManager.WIFI_MODE_SCAN_ONLY &&
                lockMode != WifiManager.WIFI_MODE_FULL_HIGH_PERF) {
            Slog.e(TAG, "Illegal argument, lockMode= " + lockMode);
            if (DBG) throw new IllegalArgumentException("lockMode=" + lockMode);
            return false;
        }
        if (ws != null && ws.size() == 0) {
            ws = null;
        }
        if (ws != null) {
            enforceWakeSourcePermission(Binder.getCallingUid(), Binder.getCallingPid());
        }
        if (ws == null) {
            ws = new WorkSource(Binder.getCallingUid());
        }
        WifiLock wifiLock = new WifiLock(lockMode, tag, binder, ws);
        synchronized (mLocks) {
            return acquireWifiLockLocked(wifiLock);
        }
    }

    private void noteAcquireWifiLock(WifiLock wifiLock) throws RemoteException {
        switch(wifiLock.mMode) {
            case WifiManager.WIFI_MODE_FULL:
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
            case WifiManager.WIFI_MODE_SCAN_ONLY:
                mBatteryStats.noteFullWifiLockAcquiredFromSource(wifiLock.mWorkSource);
                break;
        }
    }

    private void noteReleaseWifiLock(WifiLock wifiLock) throws RemoteException {
        switch(wifiLock.mMode) {
            case WifiManager.WIFI_MODE_FULL:
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
            case WifiManager.WIFI_MODE_SCAN_ONLY:
                mBatteryStats.noteFullWifiLockReleasedFromSource(wifiLock.mWorkSource);
                break;
        }
    }

    private boolean acquireWifiLockLocked(WifiLock wifiLock) {
        if (DBG) Slog.d(TAG, "acquireWifiLockLocked: " + wifiLock);

        mLocks.addLock(wifiLock);

        long ident = Binder.clearCallingIdentity();
        try {
            noteAcquireWifiLock(wifiLock);
            switch(wifiLock.mMode) {
            case WifiManager.WIFI_MODE_FULL:
                ++mFullLocksAcquired;
                break;
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                ++mFullHighPerfLocksAcquired;
                break;

            case WifiManager.WIFI_MODE_SCAN_ONLY:
                ++mScanLocksAcquired;
                break;
            }
            mWifiController.sendMessage(CMD_LOCKS_CHANGED);
            return true;
        } catch (RemoteException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void updateWifiLockWorkSource(IBinder lock, WorkSource ws) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        if (ws != null && ws.size() == 0) {
            ws = null;
        }
        if (ws != null) {
            enforceWakeSourcePermission(uid, pid);
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mLocks) {
                int index = mLocks.findLockByBinder(lock);
                if (index < 0) {
                    throw new IllegalArgumentException("Wifi lock not active");
                }
                WifiLock wl = mLocks.mList.get(index);
                noteReleaseWifiLock(wl);
                wl.mWorkSource = ws != null ? new WorkSource(ws) : new WorkSource(uid);
                noteAcquireWifiLock(wl);
            }
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean releaseWifiLock(IBinder lock) {
        SXlog.d(TAG, "releaseWifiLock, pid:" + Binder.getCallingPid()
                + ", uid:" + Binder.getCallingUid());

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        synchronized (mLocks) {
            return releaseWifiLockLocked(lock);
        }
    }

    private boolean releaseWifiLockLocked(IBinder lock) {
        boolean hadLock;

        WifiLock wifiLock = mLocks.removeLock(lock);

        if (DBG) Slog.d(TAG, "releaseWifiLockLocked: " + wifiLock);

        hadLock = (wifiLock != null);

        long ident = Binder.clearCallingIdentity();
        try {
            if (hadLock) {
                noteReleaseWifiLock(wifiLock);
                switch(wifiLock.mMode) {
                    case WifiManager.WIFI_MODE_FULL:
                        ++mFullLocksReleased;
                        break;
                    case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                        ++mFullHighPerfLocksReleased;
                        break;
                    case WifiManager.WIFI_MODE_SCAN_ONLY:
                        ++mScanLocksReleased;
                        break;
                }
                mWifiController.sendMessage(CMD_LOCKS_CHANGED);
            }
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return hadLock;
    }

    private abstract class DeathRecipient
            implements IBinder.DeathRecipient {
        String mTag;
        int mMode;
        IBinder mBinder;
        WorkSource mWorkSource;

        DeathRecipient(int mode, String tag, IBinder binder, WorkSource ws) {
            super();
            mTag = tag;
            mMode = mode;
            mBinder = binder;
            mWorkSource = ws;
            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        void unlinkDeathRecipient() {
            mBinder.unlinkToDeath(this, 0);
        }
    }

    private class Multicaster extends DeathRecipient {
        Multicaster(String tag, IBinder binder) {
            super(Binder.getCallingUid(), tag, binder, null);
        }

        public void binderDied() {
            Slog.e(TAG, "Multicaster binderDied");
            synchronized (mMulticasters) {
                int i = mMulticasters.indexOf(this);
                if (i != -1) {
                    removeMulticasterLocked(i, mMode);
                }
            }
        }

        public String toString() {
            return "Multicaster{" + mTag + " binder=" + mBinder + "}";
        }

        public int getUid() {
            return mMode;
        }
    }

    public void initializeMulticastFiltering() {
        enforceMulticastChangePermission();

        synchronized (mMulticasters) {
            // if anybody had requested filters be off, leave off
            if (mMulticasters.size() != 0) {
                return;
            } else {
                mWifiStateMachine.startFilteringMulticastV4Packets();
            }
        }
    }

    public void acquireMulticastLock(IBinder binder, String tag) {
        SXlog.d(TAG, "acquireMulticastLock, pid:" + Binder.getCallingPid()
                + ", uid:" + Binder.getCallingUid());

        enforceMulticastChangePermission();

        synchronized (mMulticasters) {
            mMulticastEnabled++;
            mMulticasters.add(new Multicaster(tag, binder));
            // Note that we could call stopFilteringMulticastV4Packets only when
            // our new size == 1 (first call), but this function won't
            // be called often and by making the stopPacket call each
            // time we're less fragile and self-healing.
            mWifiStateMachine.stopFilteringMulticastV4Packets();
        }

        int uid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteWifiMulticastEnabled(uid);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void releaseMulticastLock() {
        SXlog.d(TAG, "releaseMulticastLock, pid:" + Binder.getCallingPid()
                + ", uid:" + Binder.getCallingUid());

        enforceMulticastChangePermission();

        int uid = Binder.getCallingUid();
        synchronized (mMulticasters) {
            mMulticastDisabled++;
            int size = mMulticasters.size();
            for (int i = size - 1; i >= 0; i--) {
                Multicaster m = mMulticasters.get(i);
                if ((m != null) && (m.getUid() == uid)) {
                    removeMulticasterLocked(i, uid);
                }
            }
        }
    }

    private void removeMulticasterLocked(int i, int uid)
    {
        Multicaster removed = mMulticasters.remove(i);

        if (removed != null) {
            removed.unlinkDeathRecipient();
        }
        if (mMulticasters.size() == 0) {
            mWifiStateMachine.startFilteringMulticastV4Packets();
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteWifiMulticastDisabled(uid);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean isMulticastEnabled() {
        enforceAccessPermission();

        synchronized (mMulticasters) {
            return (mMulticasters.size() > 0);
        }
    }

    public WifiMonitor getWifiMonitor() {
        return mWifiStateMachine.getWifiMonitor();
    }

    public void enableVerboseLogging(int verbose) {
        enforceAccessPermission();
        mWifiStateMachine.enableVerboseLogging(verbose);
    }

    public int getVerboseLoggingLevel() {
        enforceAccessPermission();
        return mWifiStateMachine.getVerboseLoggingLevel();
    }

    public void enableAggressiveHandover(int enabled) {
        enforceAccessPermission();
        mWifiStateMachine.enableAggressiveHandover(enabled);
    }

    public int getAggressiveHandover() {
        enforceAccessPermission();
        return mWifiStateMachine.getAggressiveHandover();
    }

    public void setAllowScansWithTraffic(int enabled) {
        enforceAccessPermission();
        mWifiStateMachine.setAllowScansWithTraffic(enabled);
    }

    public int getAllowScansWithTraffic() {
        enforceAccessPermission();
        return mWifiStateMachine.getAllowScansWithTraffic();
    }

    /* Return the Wifi Connection statistics object */
    public WifiConnectionStatistics getConnectionStatistics() {
        enforceAccessPermission();
        enforceReadCredentialPermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncGetConnectionStatistics(mWifiStateMachineChannel);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return null;
        }
    }

  // M: Added functions
  /**
   * Return the list of channels that are allowed
   * to be used in the current regulatory domain.
   * @return the list of allowed channels, or null if an error occurs
   */
    public String[] getAccessPointPreferredChannels() {
      enforceAccessPermission();
      String[] preferredChannels = null;
      IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
      INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);
      if (service != null) {
          try {
              preferredChannels = service.getSoftApPreferredChannel();
          } catch (RemoteException e) {
              SXlog.e(TAG, "Error get allowed channel list:" + e);
          }
      }

      return preferredChannels;
  }

  public boolean doCtiaTestOn() {
      enforceChangePermission();
      if (mWifiStateMachineChannel != null) {
          return mWifiStateMachine.syncDoCtiaTestOn(mWifiStateMachineChannel);
      } else {
          SXlog.e(TAG, "mWifiStateMachineChannel is not initialized!");
          return false;
      }
  }

  public boolean doCtiaTestOff() {
      enforceChangePermission();
      if (mWifiStateMachineChannel != null) {
          return mWifiStateMachine.syncDoCtiaTestOff(mWifiStateMachineChannel);
      } else {
          SXlog.e(TAG, "mWifiStateMachineChannel is not initialized!");
          return false;
      }
  }

  public boolean doCtiaTestRate(int rate) {
      enforceChangePermission();
      if (mWifiStateMachineChannel != null) {
          return mWifiStateMachine.syncDoCtiaTestRate(mWifiStateMachineChannel, rate);
      } else {
          SXlog.e(TAG, "mWifiStateMachineChannel is not initialized!");
          return false;
      }
  }

  public boolean setTxPowerEnabled(boolean enable) {
      enforceChangePermission();
      if (mWifiStateMachineChannel != null) {
          return mWifiStateMachine.syncSetTxPowerEnabled(mWifiStateMachineChannel, enable);
      } else {
          SXlog.e(TAG, "mWifiStateMachineChannel is not initialized!");
          return false;
      }
  }

  public boolean setTxPower(int offset) {
      enforceChangePermission();
      if (mWifiStateMachineChannel != null) {
          return mWifiStateMachine.syncSetTxPower(mWifiStateMachineChannel, offset);
      } else {
          SXlog.e(TAG, "mWifiStateMachineChannel is not initialized!");
          return false;
      }
  }

  public void addSimCardAuthenticationService(String name, IBinder binder) {
      enforceChangePermission();
      ServiceManager.addService(name, binder);
  }

  public void startApWps(WpsInfo config) {
      enforceChangePermission();
      mWifiStateMachine.startApWpsCommand(config);
  }

  public List<HotspotClient> getHotspotClients() {
      enforceAccessPermission();
      return mWifiStateMachine.syncGetHotspotClientsList();
  }

  public String getClientIp(String deviceAddress) {
      enforceAccessPermission();
      if (TextUtils.isEmpty(deviceAddress)) {
          return null;
      }
    for (String s : readClientList(LEASES_FILE)) {
          if (s.indexOf(deviceAddress) != -1) {
              String[] fields = s.split(" ");
              if (fields.length > 3) {
                  return fields[2];
              }
          }
      }
      return null;
  }

  public boolean blockClient(HotspotClient client) {
      enforceChangePermission();
      if (mWifiStateMachineChannel != null) {
          return mWifiStateMachine.syncBlockClient(mWifiStateMachineChannel, client);
      } else {
          SXlog.e(TAG, "mWifiStateMachineChannel is not initialized!");
          return false;
      }
  }

  public boolean unblockClient(HotspotClient client) {
      enforceChangePermission();
      if (mWifiStateMachineChannel != null) {
          return mWifiStateMachine.syncUnblockClient(mWifiStateMachineChannel, client);
      } else {
          SXlog.e(TAG, "mWifiStateMachineChannel is not initialized!");
          return false;
      }
  }

  public boolean setApProbeRequestEnabled(boolean enable) {
      enforceChangePermission();
      if (mWifiStateMachineChannel != null) {
          return mWifiStateMachine.syncSetApProbeRequestEnabled(mWifiStateMachineChannel, enable);
      } else {
          SXlog.e(TAG, "mWifiStateMachineChannel is not initialized!");
          return false;
      }
  }

  public void suspendNotification(int type) {
      enforceChangePermission();
      mWifiStateMachine.suspendNotification(type);
  }

  public boolean hasConnectableAp() {
      enforceAccessPermission();
      boolean value = mSettingsStore.hasConnectableAp();
      if (!value) { return false; }
      boolean result = mWifiStateMachine.hasConnectableAp();
      if (result) {
          mNotificationController.setWaitForScanResult(true);
      }
      return result;
  }

  private void initializeExtra() {

      mContext.registerReceiver(
          new BroadcastReceiver() {
              @Override
              public void onReceive(Context context, Intent intent) {
                  autoConnectEnableAllNetworks();
              }
          },
          new IntentFilter(IWifiFwkExt.AUTOCONNECT_ENABLE_ALL_NETWORKS));
  }

  private ArrayList<String> readClientList(String filename) {
      FileInputStream fstream = null;
      ArrayList<String> list = new ArrayList<String>();
      try {
          fstream = new FileInputStream(filename);
          DataInputStream in = new DataInputStream(fstream);
          BufferedReader br = new BufferedReader(new InputStreamReader(in));
          String s;
          // throw away the title line
          while (((s = br.readLine()) != null) && (s.length() != 0)) {
              list.add(s);
          }
      } catch (IOException ex) {
          // return current list, possibly empty
          SXlog.e(TAG, "IOException:" + ex);
      } finally {
          if (fstream != null) {
              try {
                  fstream.close();
              } catch (IOException ex) {
                  SXlog.e(TAG, "IOException:" + ex);
              }
          }
      }
      return list;
  }

  private void autoConnectEnableAllNetworks() {
      boolean isConnecting = mWifiStateMachine.isNetworksDisabledDuringConnect();
      SXlog.d(TAG, "autoConnectEnableAllNetworks, isConnecting:" + isConnecting);
      boolean autoConnect = false;
      if (!isConnecting) {
          List<WifiConfiguration> networks = getConfiguredNetworks();
          WifiInfo wifiInfo = mWifiStateMachine.syncRequestConnectionInfo();
          if (null != networks) {
              Collections.sort(networks, new Comparator<WifiConfiguration>() {
                  public int compare(WifiConfiguration obj1, WifiConfiguration obj2) {
                      return obj2.priority - obj1.priority;
                  }
              });
              List<Integer> disconnectNetworks = mWifiStateMachine.syncGetDisconnectNetworks();
              for (WifiConfiguration network : networks) {
                  if (wifiInfo != null && network.networkId != wifiInfo.getNetworkId()
                      && network.disableReason == WifiConfiguration.DISABLED_UNKNOWN_REASON
                      && !disconnectNetworks.contains(network.networkId)) {
                      enableNetwork(network.networkId, false);
                      autoConnect = true;
                  }
              }
              if (autoConnect) {
                  if (!mWifiStateMachine.syncGetDisconnectFlag(mWifiStateMachineChannel)) {
                      reconnect();
                  }
                  saveConfiguration();
              }
          }
      }
  }


  public int syncGetConnectingNetworkId() {
      if (mWifiStateMachineChannel != null) {
          return mWifiStateMachine.syncGetConnectingNetworkId(mWifiStateMachineChannel);
      } else {
          SXlog.e(TAG, "mWifiStateMachineChannel is not initialized!");
          return -1;
      }
  }

  class wifiOnPermissionCheckCallback extends IRequestedPermissionCallback.Stub {
      @Override
      public void onPermissionCheckResult(String permissionName, int uid, int result) {
          if (result == PackageManager.PERMISSION_GRANTED) {
              Slog.d(TAG, "setWifiEnabled(PermissionCheckCallback): " + ", uid=" + uid + " result=" + result);
              boolean enable = true;
              if (result != PackageManager.PERMISSION_GRANTED) {
                  return;
              }

              /*
               * Caller might not have WRITE_SECURE_SETTINGS,
               * only CHANGE_WIFI_STATE is enforced
               */
              long ident = Binder.clearCallingIdentity();
              try {
                  if (!mSettingsStore.handleWifiToggled(enable)) {
                      // Nothing to do if wifi cannot be toggled
                      return;
                  }
              } finally {
                  Binder.restoreCallingIdentity(ident);
              }

              ///M: put extra mWifiIpoOff
              mWifiController.obtainMessage(CMD_WIFI_TOGGLED, mWifiIpoOff ? 1 : 0).sendToTarget();
              return;
           }
      };
  };

  public double getPoorLinkThreshold(boolean isGood) {
      enforceAccessPermission();
      SXlog.d(TAG, "getPoorLinkThreshold, isGood:" + isGood);

      return mWifiWatchdogStateMachine.getPoorLinkThreshold(isGood);

  }

  public boolean setPoorLinkThreshold(String key, double value) {
      enforceAccessPermission();
      SXlog.d(TAG, "setPoorLinkThreshold, key:" + key + " value= " + value);
      if (key.equals("goodlink")) {
          return mWifiWatchdogStateMachine.setPoorLinkThreshold(true, value);
      } else if (key.equals("poorlink")) {
          return mWifiWatchdogStateMachine.setPoorLinkThreshold(false, value);
      } else if (key.equals("rssi") || key.equals("linkspeed")) {
          return mWifiStateMachine.syncSetPoorlinkThreshold(mWifiStateMachineChannel, key, value);
      }
      return false;
  }

  public void setPoorLinkProfilingOn(boolean enable) {
      enforceAccessPermission();
      SXlog.d(TAG, "setPoorLinkProfilingOn:" + enable);
      mWifiWatchdogStateMachine.setPoorLinkProfilingOn(enable);
  }
  public String getWifiStatus() {
      enforceAccessPermission();
      String result = "";
      if (mWifiStateMachineChannel != null) {
          result = mWifiStateMachine.getWifiStatus(mWifiStateMachineChannel);
      } else {
          SXlog.e(TAG, "mWifiStateMachineChannel is not initialized!");
      }
      return result;
  }

  public void setPowerSavingMode(boolean mode) {
      enforceAccessPermission();
      mWifiStateMachine.setPowerSavingMode(mode);
  }

    public PPPOEInfo getPPPOEInfo() {
        enforceAccessPermission();
        return mWifiStateMachine.syncGetPppoeInfo();
    }
    ///M: Add API For Set WOWLAN Mode
    public boolean setWoWlanNormalMode() {
        enforceChangePermission();
        Slog.d(TAG, "setWoWlanNormalMode");
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncSetWoWlanNormalMode(mWifiStateMachineChannel);
        } else {
            SXlog.e(TAG, "mWifiStateMachineChannel is not initialized!");
            return false;
        }
    }
    ///M: Add API For Set WOWLAN Mode
    public boolean setWoWlanMagicMode() {
        enforceChangePermission();
        Slog.d(TAG, "setWoWlanMagicMode");
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncSetWoWlanMagicMode(mWifiStateMachineChannel);
        } else {
            SXlog.e(TAG, "mWifiStateMachineChannel is not initialized!");
            return false;
        }
    }

    /**
     * Returns true if the connectivity IC supports 5G band.
     * @return true menas the Wi-Fi 5G band is supported.
     */
    @SuppressWarnings(value = {"localfinalvariablename" })
    public boolean is5gBandSupported() {
        final int NARAM_5G_BAND_SUPPORT = 0xc5;
        final int NARAM_5G_BAND_ENABLE = 0x106;
        final String MAC_ADDRESS_FILENAME = "/data/nvram/APCFG/APRDEB/WIFI";
        final String NVRAM_AGENT_SERVICE = "NvRAMAgent";
        int wifi5gBandSupported = 0;

        try {
            WifiNvRamAgent agent = WifiNvRamAgent.Stub.asInterface(
                    ServiceManager.getService(NVRAM_AGENT_SERVICE));
            byte[] buffer = agent.readFileByName(MAC_ADDRESS_FILENAME);
            wifi5gBandSupported = buffer[NARAM_5G_BAND_SUPPORT] & buffer[NARAM_5G_BAND_ENABLE];
            Log.i(TAG, "wifiSupport5g:" + wifi5gBandSupported
                + ":" + buffer[NARAM_5G_BAND_SUPPORT] + ":" + buffer[NARAM_5G_BAND_ENABLE]);
        } catch (RemoteException e) {
            e.printStackTrace();
        } catch (IndexOutOfBoundsException ee) {
            ee.printStackTrace();
        }
        return (wifi5gBandSupported == 1) ? true : false;
    }


   /**
     * Returns true  if set hotspot optimization success.
     *@param enable set enable true means do not reconnect and scan.
     * @return true menas  set hotspot optimization success.
     */
    @SuppressWarnings(value = {"localfinalvariablename" })
    public boolean setHotspotOptimization(boolean enable) {
       mWifiStateMachine.setHotspotOptimization(enable);
       return true;
    }
   /**
     * Returns true  if set auto join scan when connected success.
     *@param enable set enable true means do not reconnect and scan.
     * @return true menas  set auto join scan when connected success.
     */
    @SuppressWarnings(value = {"localfinalvariablename" })
    public boolean setAutoJoinScanWhenConnected(boolean enable) {
       mWifiStateMachine.setAutoJoinScanWhenConnected(enable);
       return true;
    }

    /**
     * Get test environment.
     * @param channel Wi-Fi channel
     * @return test environment string
     */
    public String getTestEnv(int channel) {
        enforceAccessPermission();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncGetTestEnv(mWifiStateMachineChannel, channel);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return null;
        }
    }
}
