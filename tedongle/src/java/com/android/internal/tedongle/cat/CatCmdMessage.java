/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.tedongle.cat;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Class used to pass CAT messages from telephony to application. Application
 * should call getXXX() to get commands's specific values.
 *
 */
public class CatCmdMessage implements Parcelable {
    // members
    CommandDetails mCmdDet;
    private TextMessage mTextMsg;
    private Menu mMenu;
    private Input mInput;
    private BrowserSettings mBrowserSettings = null;
    private ToneSettings mToneSettings = null;
    private CallSettings mCallSettings = null;

    /*
     * Container for Launch Browser command settings.
     */
    public class BrowserSettings {
        public String url;
        public LaunchBrowserMode mode;
    }

    /*
     * Container for Call Setup command settings.
     */
    public class CallSettings {
        public TextMessage confirmMsg;
        public TextMessage callMsg;
    }

    CatCmdMessage(CommandParams cmdParams) {
        mCmdDet = cmdParams.mCmdDet;
        switch(getCmdType()) {
        case SET_UP_MENU:
        case SELECT_ITEM:
            mMenu = ((SelectItemParams) cmdParams).mMenu;
            break;
        case DISPLAY_TEXT:
        case SET_UP_IDLE_MODE_TEXT:
        case SEND_DTMF:
        case SEND_SMS:
        case SEND_SS:
        case SEND_USSD:
            mTextMsg = ((DisplayTextParams) cmdParams).mTextMsg;
            break;
        case GET_INPUT:
        case GET_INKEY:
            mInput = ((GetInputParams) cmdParams).mInput;
            break;
        case LAUNCH_BROWSER:
            mTextMsg = ((LaunchBrowserParams) cmdParams).mConfirmMsg;
            mBrowserSettings = new BrowserSettings();
            mBrowserSettings.url = ((LaunchBrowserParams) cmdParams).mUrl;
            mBrowserSettings.mode = ((LaunchBrowserParams) cmdParams).mMode;
            break;
        case PLAY_TONE:
            PlayToneParams params = (PlayToneParams) cmdParams;
            mToneSettings = params.mSettings;
            mTextMsg = params.mTextMsg;
            break;
        case GET_CHANNEL_STATUS:
            mTextMsg = ((CallSetupParams) cmdParams).mConfirmMsg;
            break;
        case SET_UP_CALL:
            mCallSettings = new CallSettings();
            mCallSettings.confirmMsg = ((CallSetupParams) cmdParams).mConfirmMsg;
            mCallSettings.callMsg = ((CallSetupParams) cmdParams).mCallMsg;
            break;
        case OPEN_CHANNEL:
        case CLOSE_CHANNEL:
        case RECEIVE_DATA:
        case SEND_DATA:
            BIPClientParams param = (BIPClientParams) cmdParams;
            mTextMsg = param.mTextMsg;
            break;
        case PROVIDE_LOCAL_INFORMATION:
        case REFRESH:
        case SET_UP_EVENT_LIST:
        default:
            break;
        }
    }

    public CatCmdMessage(Parcel in) {
        mCmdDet = in.readParcelable(null);
        mTextMsg = in.readParcelable(null);
        mMenu = in.readParcelable(null);
        mInput = in.readParcelable(null);
        switch (getCmdType()) {
        case LAUNCH_BROWSER:
            mBrowserSettings = new BrowserSettings();
            mBrowserSettings.url = in.readString();
            mBrowserSettings.mode = LaunchBrowserMode.values()[in.readInt()];
            break;
        case PLAY_TONE:
            mToneSettings = in.readParcelable(null);
            break;
        case SET_UP_CALL:
            mCallSettings = new CallSettings();
            mCallSettings.confirmMsg = in.readParcelable(null);
            mCallSettings.callMsg = in.readParcelable(null);
            break;
        default:
            break;
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mCmdDet, 0);
        dest.writeParcelable(mTextMsg, 0);
        dest.writeParcelable(mMenu, 0);
        dest.writeParcelable(mInput, 0);
        switch(getCmdType()) {
        case LAUNCH_BROWSER:
            dest.writeString(mBrowserSettings.url);
            dest.writeInt(mBrowserSettings.mode.ordinal());
            break;
        case PLAY_TONE:
            dest.writeParcelable(mToneSettings, 0);
            break;
        case SET_UP_CALL:
            dest.writeParcelable(mCallSettings.confirmMsg, 0);
            dest.writeParcelable(mCallSettings.callMsg, 0);
            break;
        default:
            break;
        }
    }

    public static final Parcelable.Creator<CatCmdMessage> CREATOR = new Parcelable.Creator<CatCmdMessage>() {
        @Override
        public CatCmdMessage createFromParcel(Parcel in) {
            return new CatCmdMessage(in);
        }

        @Override
        public CatCmdMessage[] newArray(int size) {
            return new CatCmdMessage[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    /* external API to be used by application */
    public AppInterface.CommandType getCmdType() {
        return AppInterface.CommandType.fromInt(mCmdDet.typeOfCommand);
    }

    public Menu getMenu() {
        return mMenu;
    }

    public Input geInput() {
        return mInput;
    }

    public TextMessage geTextMessage() {
        return mTextMsg;
    }

    public BrowserSettings getBrowserSettings() {
        return mBrowserSettings;
    }

    public ToneSettings getToneSettings() {
        return mToneSettings;
    }

    public CallSettings getCallSettings() {
        return mCallSettings;
    }
}
