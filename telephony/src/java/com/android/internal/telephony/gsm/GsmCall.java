/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DriverCall;
import com.android.internal.telephony.Phone;

import java.util.List;

/**
 * {@hide}
 */
class GsmCall extends Call {
    /*************************** Instance Variables **************************/

    /*package*/ GsmCallTracker mOwner;

    /****************************** Constructors *****************************/
    /*package*/
    GsmCall (GsmCallTracker owner) {
        mOwner = owner;
    }

    public void dispose() {
    }

    /************************** Overridden from Call *************************/

    @Override
    public List<Connection>
    getConnections() {
        // FIXME should return Collections.unmodifiableList();
        return mConnections;
    }

    @Override
    public Phone
    getPhone() {
        return mOwner.mPhone;
    }

    @Override
    public boolean
    isMultiparty() {
        /// M: CC014: Remove DISCONNECTED & DIALING conn from counting isMultiparty @{
        int DiscConn = 0;
        boolean isMptyCall = false;

        for (int j = mConnections.size() - 1 ; j >= 0 ; j--) {
            GsmConnection cn = (GsmConnection) (mConnections.get(j));

            if (cn.getState() == GsmCall.State.DISCONNECTED) {
                DiscConn++;
            }
        }

        if (mConnections.size() <= 1) {
            isMptyCall = false;
        } else if (mConnections.size() > 1) {
            if ((mConnections.size() - DiscConn) <= 1) {
                isMptyCall = false;
            } else if (getState() == GsmCall.State.DIALING) {
                isMptyCall = false;
            } else {
                isMptyCall = true;
            }
        } else {
            isMptyCall = false;
        }

        return isMptyCall;
        /// @}
    }

    /** Please note: if this is the foreground call and a
     *  background call exists, the background call will be resumed
     *  because an AT+CHLD=1 will be sent
     */
    @Override
    public void
    hangup() throws CallStateException {
        mOwner.hangup(this);
    }

    /// M: CC040: Reject call with cause for HFP @{
    /** Please note: if this is the foreground call and a
     *  background call exists, the background call will be resumed
     *  because an AT+CHLD=1 will be sent
     *  Add this API by mtk01411 [ALPS00475147]
     */
    public void
    hangup(int discRingingCallCause) throws CallStateException {
        mOwner.hangup(this, discRingingCallCause);
    }
    /// @}

    @Override
    public String
    toString() {
        return mState.toString();
    }

    //***** Called from GsmConnection

    /*package*/ void
    attach(Connection conn, DriverCall dc) {
        mConnections.add(conn);

        mState = stateFromDCState (dc.state);
    }

    /*package*/ void
    attachFake(Connection conn, State state) {
        mConnections.add(conn);

        mState = state;
    }

    /**
     * Called by GsmConnection when it has disconnected
     */
    boolean
    connectionDisconnected(GsmConnection conn) {
        if (mState != State.DISCONNECTED) {
            /* If only disconnected connections remain, we are disconnected*/

            boolean hasOnlyDisconnectedConnections = true;

            for (int i = 0, s = mConnections.size()  ; i < s; i ++) {
                if (mConnections.get(i).getState()
                    != State.DISCONNECTED
                ) {
                    hasOnlyDisconnectedConnections = false;
                    break;
                }
            }

            if (hasOnlyDisconnectedConnections) {
                mState = State.DISCONNECTED;
                return true;
            }
        }

        return false;
    }

    /*package*/ void
    detach(GsmConnection conn) {
        mConnections.remove(conn);

        if (mConnections.size() == 0) {
            mState = State.IDLE;
        }
    }

    /*package*/ boolean
    update (GsmConnection conn, DriverCall dc) {
        State newState;
        boolean changed = false;

        newState = stateFromDCState(dc.state);

        if (newState != mState) {
            mState = newState;
            changed = true;
        }

        return changed;
    }

    /**
     * @return true if there's no space in this call for additional
     * connections to be added via "conference"
     */
    /*package*/ boolean
    isFull() {
        return mConnections.size() == GsmCallTracker.MAX_CONNECTIONS_PER_CALL;
    }

    //***** Called from GsmCallTracker


    /**
     * Called when this Call is being hung up locally (eg, user pressed "end")
     * Note that at this point, the hangup request has been dispatched to the radio
     * but no response has yet been received so update() has not yet been called
     */
    void
    onHangupLocal() {
        for (int i = 0, s = mConnections.size()
                ; i < s; i++
        ) {
            GsmConnection cn = (GsmConnection)mConnections.get(i);

            cn.onHangupLocal();
        }

        if ((mConnections.size() != 0) && getState().isAlive()) {
           mState = State.DISCONNECTING;
        }
    }

    /**
     * Called when it's time to clean up disconnected Connection objects
     */
    void
    clearDisconnected() {
        for (int i = mConnections.size() - 1 ; i >= 0 ; i--) {
            GsmConnection cn = (GsmConnection)mConnections.get(i);

            if (cn.getState() == State.DISCONNECTED) {
                mConnections.remove(i);
            }
        }

        if (mConnections.size() == 0) {
            mState = State.IDLE;
        }
    }
}

