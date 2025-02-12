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

package com.android.internal.telephony.dataconnection;

import static android.net.NetworkPolicyManager.OVERRIDE_CONGESTED;
import static android.net.NetworkPolicyManager.OVERRIDE_UNMETERED;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.KeepalivePacketData;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkMisc;
import android.net.NetworkRequest;
import android.net.NetworkUtils;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.SocketKeepalive;
import android.net.StringNetworkSpecifier;
import android.os.AsyncResult;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Telephony;
import android.telephony.AccessNetworkConstants;
import android.telephony.AccessNetworkConstants.TransportType;
import android.telephony.CarrierConfigManager;
import android.telephony.DataFailCause;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.telephony.data.ApnSetting.ApnType;
import android.telephony.data.DataCallResponse;
import android.telephony.data.DataProfile;
import android.telephony.data.DataService;
import android.telephony.data.DataServiceCallback;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Pair;
import android.util.StatsLog;
import android.util.TimeUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CarrierSignalAgent;
import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.LinkCapacityEstimate;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.RetryManager;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.dataconnection.DcTracker.ReleaseNetworkType;
import com.android.internal.telephony.dataconnection.DcTracker.RequestNetworkType;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.nano.TelephonyProto.RilDataCall;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@hide}
 *
 * DataConnection StateMachine.
 *
 * This a class for representing a single data connection, with instances of this
 * class representing a connection via the cellular network. There may be multiple
 * data connections and all of them are managed by the <code>DataConnectionTracker</code>.
 *
 * NOTE: All DataConnection objects must be running on the same looper, which is the default
 * as the coordinator has members which are used without synchronization.
 */
public class DataConnection extends StateMachine {
    protected static final boolean DBG = true;
    protected static final boolean VDBG = true;

    private static final String NETWORK_TYPE = "MOBILE";

    private static final String RAT_NAME_5G = "nr";
    private static final String RAT_NAME_EVDO = "evdo";

    /**
     * The data connection is not being or been handovered. Note this is the state for the source
     * data connection, not destination data connection
     */
    private static final int HANDOVER_STATE_IDLE = 1;

    /**
     * The data connection is being handovered. Note this is the state for the source
     * data connection, not destination data connection.
     */
    private static final int HANDOVER_STATE_BEING_TRANSFERRED = 2;

    /**
     * The data connection is already handovered. Note this is the state for the source
     * data connection, not destination data connection.
     */
    private static final int HANDOVER_STATE_COMPLETED = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"HANDOVER_STATE_"}, value = {
            HANDOVER_STATE_IDLE,
            HANDOVER_STATE_BEING_TRANSFERRED,
            HANDOVER_STATE_COMPLETED})
    public @interface HandoverState {}

    // The data connection providing default Internet connection will have a higher score of 50.
    // Other connections will have a slightly lower score of 45. The intention is other connections
    // will not cause ConnectivityService to tear down default internet connection. For example,
    // to validate Internet connection on non-default data SIM, we'll set up a temporary Internet
    // connection on that data SIM. In this case, score of 45 is assigned so ConnectivityService
    // will not replace the default Internet connection with it.
    private static final int DEFAULT_INTERNET_CONNECTION_SCORE = 50;
    private static final int OTHER_CONNECTION_SCORE = 45;

    // The score we report to connectivity service
    private int mScore;

    // The subscription id associated with this data connection.
    private int mSubId;

    // The data connection controller
    private DcController mDcController;

    // The Tester for failing all bringup's
    private DcTesterFailBringUpAll mDcTesterFailBringUpAll;

    protected static AtomicInteger mInstanceNumber = new AtomicInteger(0);
    private AsyncChannel mAc;

    // The DCT that's talking to us, we only support one!
    private DcTracker mDct = null;

    private String[] mPcscfAddr;

    private final String mTagSuffix;

    private final LocalLog mHandoverLocalLog = new LocalLog(100);

    /**
     * Used internally for saving connecting parameters.
     */
    public static class ConnectionParams {
        int mTag;
        public ApnContext mApnContext;
        int mProfileId;
        int mRilRat;
        Message mOnCompletedMsg;
        final int mConnectionGeneration;
        @RequestNetworkType
        final int mRequestType;
        final int mSubId;
        final boolean mIsApnPreferred;

        ConnectionParams(ApnContext apnContext, int profileId, int rilRadioTechnology,
                         Message onCompletedMsg, int connectionGeneration,
                         @RequestNetworkType int requestType, int subId,
                         boolean isApnPreferred) {
            mApnContext = apnContext;
            mProfileId = profileId;
            mRilRat = rilRadioTechnology;
            mOnCompletedMsg = onCompletedMsg;
            mConnectionGeneration = connectionGeneration;
            mRequestType = requestType;
            mSubId = subId;
            mIsApnPreferred = isApnPreferred;
        }

        @Override
        public String toString() {
            return "{mTag=" + mTag + " mApnContext=" + mApnContext
                    + " mProfileId=" + mProfileId
                    + " mRat=" + mRilRat
                    + " mOnCompletedMsg=" + msgToString(mOnCompletedMsg)
                    + " mRequestType=" + DcTracker.requestTypeToString(mRequestType)
                    + " mSubId=" + mSubId
                    + " mIsApnPreferred=" + mIsApnPreferred
                    + "}";
        }
    }

    /**
     * Used internally for saving disconnecting parameters.
     */
    public static class DisconnectParams {
        int mTag;
        public ApnContext mApnContext;
        String mReason;
        @ReleaseNetworkType
        final int mReleaseType;
        Message mOnCompletedMsg;

        DisconnectParams(ApnContext apnContext, String reason, @ReleaseNetworkType int releaseType,
                         Message onCompletedMsg) {
            mApnContext = apnContext;
            mReason = reason;
            mReleaseType = releaseType;
            mOnCompletedMsg = onCompletedMsg;
        }

        @Override
        public String toString() {
            return "{mTag=" + mTag + " mApnContext=" + mApnContext
                    + " mReason=" + mReason
                    + " mReleaseType=" + DcTracker.releaseTypeToString(mReleaseType)
                    + " mOnCompletedMsg=" + msgToString(mOnCompletedMsg) + "}";
        }
    }

    private ApnSetting mApnSetting;
    private ConnectionParams mConnectionParams;
    private DisconnectParams mDisconnectParams;
    @DataFailCause.FailCause
    private int mDcFailCause;

    protected Phone mPhone;
    private DataServiceManager mDataServiceManager;
    protected final int mTransportType;
    private LinkProperties mLinkProperties = new LinkProperties();
    private long mCreateTime;
    private long mLastFailTime;
    @DataFailCause.FailCause
    private int mLastFailCause;
    private static final String NULL_IP = "0.0.0.0";
    private Object mUserData;
    private int mSubscriptionOverride;
    private int mRilRat = ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN;
    private boolean mUnmeteredOverride;
    private int mDataRegState = Integer.MAX_VALUE;
    private NetworkInfo mNetworkInfo;

    /** The corresponding network agent for this data connection. */
    private DcNetworkAgent mNetworkAgent;

    /**
     * The network agent from handover source data connection. This is the potential network agent
     * that will be transferred here after handover completed.
     */
    private DcNetworkAgent mHandoverSourceNetworkAgent;

    private int mDisabledApnTypeBitMask = 0;

    protected int mTag;

    /** Data connection id assigned by the modem. This is unique across transports */
    public int mCid;

    @HandoverState
    private int mHandoverState;
    private final Map<ApnContext, ConnectionParams> mApnContexts = new ConcurrentHashMap<>();
    PendingIntent mReconnectIntent = null;

    private boolean mRegistered = false;


    // ***** Event codes for driving the state machine, package visible for Dcc
    static final int BASE = Protocol.BASE_DATA_CONNECTION;
    static final int EVENT_CONNECT = BASE + 0;
    static final int EVENT_SETUP_DATA_CONNECTION_DONE = BASE + 1;
    static final int EVENT_DEACTIVATE_DONE = BASE + 3;
    static final int EVENT_DISCONNECT = BASE + 4;
    static final int EVENT_RIL_CONNECTED = BASE + 5;
    static final int EVENT_DISCONNECT_ALL = BASE + 6;
    static final int EVENT_DATA_STATE_CHANGED = BASE + 7;
    static final int EVENT_TEAR_DOWN_NOW = BASE + 8;
    static final int EVENT_LOST_CONNECTION = BASE + 9;
    static final int EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED = BASE + 11;
    static final int EVENT_DATA_CONNECTION_ROAM_ON = BASE + 12;
    static final int EVENT_DATA_CONNECTION_ROAM_OFF = BASE + 13;
    static final int EVENT_BW_REFRESH_RESPONSE = BASE + 14;
    static final int EVENT_DATA_CONNECTION_VOICE_CALL_STARTED = BASE + 15;
    static final int EVENT_DATA_CONNECTION_VOICE_CALL_ENDED = BASE + 16;
    static final int EVENT_DATA_CONNECTION_OVERRIDE_CHANGED = BASE + 17;
    static final int EVENT_KEEPALIVE_STATUS = BASE + 18;
    static final int EVENT_KEEPALIVE_STARTED = BASE + 19;
    static final int EVENT_KEEPALIVE_STOPPED = BASE + 20;
    static final int EVENT_KEEPALIVE_START_REQUEST = BASE + 21;
    static final int EVENT_KEEPALIVE_STOP_REQUEST = BASE + 22;
    static final int EVENT_LINK_CAPACITY_CHANGED = BASE + 23;
    static final int EVENT_RESET = BASE + 24;
    static final int EVENT_REEVALUATE_RESTRICTED_STATE = BASE + 25;
    static final int EVENT_REEVALUATE_DATA_CONNECTION_PROPERTIES = BASE + 26;
    static final int EVENT_NR_STATE_CHANGED = BASE + 27;
    static final int EVENT_DATA_CONNECTION_METEREDNESS_CHANGED = BASE + 28;
    static final int EVENT_NR_FREQUENCY_CHANGED = BASE + 29;
    protected static final int EVENT_RETRY_CONNECTION = BASE + 30;
    private static final int CMD_TO_STRING_COUNT = EVENT_RETRY_CONNECTION - BASE + 1;

    private static String[] sCmdToString = new String[CMD_TO_STRING_COUNT];
    static {
        sCmdToString[EVENT_CONNECT - BASE] = "EVENT_CONNECT";
        sCmdToString[EVENT_SETUP_DATA_CONNECTION_DONE - BASE] =
                "EVENT_SETUP_DATA_CONNECTION_DONE";
        sCmdToString[EVENT_DEACTIVATE_DONE - BASE] = "EVENT_DEACTIVATE_DONE";
        sCmdToString[EVENT_DISCONNECT - BASE] = "EVENT_DISCONNECT";
        sCmdToString[EVENT_RIL_CONNECTED - BASE] = "EVENT_RIL_CONNECTED";
        sCmdToString[EVENT_DISCONNECT_ALL - BASE] = "EVENT_DISCONNECT_ALL";
        sCmdToString[EVENT_DATA_STATE_CHANGED - BASE] = "EVENT_DATA_STATE_CHANGED";
        sCmdToString[EVENT_TEAR_DOWN_NOW - BASE] = "EVENT_TEAR_DOWN_NOW";
        sCmdToString[EVENT_LOST_CONNECTION - BASE] = "EVENT_LOST_CONNECTION";
        sCmdToString[EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED - BASE] =
                "EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED";
        sCmdToString[EVENT_DATA_CONNECTION_ROAM_ON - BASE] = "EVENT_DATA_CONNECTION_ROAM_ON";
        sCmdToString[EVENT_DATA_CONNECTION_ROAM_OFF - BASE] = "EVENT_DATA_CONNECTION_ROAM_OFF";
        sCmdToString[EVENT_BW_REFRESH_RESPONSE - BASE] = "EVENT_BW_REFRESH_RESPONSE";
        sCmdToString[EVENT_DATA_CONNECTION_VOICE_CALL_STARTED - BASE] =
                "EVENT_DATA_CONNECTION_VOICE_CALL_STARTED";
        sCmdToString[EVENT_DATA_CONNECTION_VOICE_CALL_ENDED - BASE] =
                "EVENT_DATA_CONNECTION_VOICE_CALL_ENDED";
        sCmdToString[EVENT_DATA_CONNECTION_OVERRIDE_CHANGED - BASE] =
                "EVENT_DATA_CONNECTION_OVERRIDE_CHANGED";
        sCmdToString[EVENT_KEEPALIVE_STATUS - BASE] = "EVENT_KEEPALIVE_STATUS";
        sCmdToString[EVENT_KEEPALIVE_STARTED - BASE] = "EVENT_KEEPALIVE_STARTED";
        sCmdToString[EVENT_KEEPALIVE_STOPPED - BASE] = "EVENT_KEEPALIVE_STOPPED";
        sCmdToString[EVENT_KEEPALIVE_START_REQUEST - BASE] = "EVENT_KEEPALIVE_START_REQUEST";
        sCmdToString[EVENT_KEEPALIVE_STOP_REQUEST - BASE] = "EVENT_KEEPALIVE_STOP_REQUEST";
        sCmdToString[EVENT_LINK_CAPACITY_CHANGED - BASE] = "EVENT_LINK_CAPACITY_CHANGED";
        sCmdToString[EVENT_RESET - BASE] = "EVENT_RESET";
        sCmdToString[EVENT_REEVALUATE_RESTRICTED_STATE - BASE] =
                "EVENT_REEVALUATE_RESTRICTED_STATE";
        sCmdToString[EVENT_REEVALUATE_DATA_CONNECTION_PROPERTIES - BASE] =
                "EVENT_REEVALUATE_DATA_CONNECTION_PROPERTIES";
        sCmdToString[EVENT_NR_STATE_CHANGED - BASE] = "EVENT_NR_STATE_CHANGED";
        sCmdToString[EVENT_RETRY_CONNECTION - BASE] = "EVENT_RETRY_CONNECTION";
        sCmdToString[EVENT_DATA_CONNECTION_METEREDNESS_CHANGED - BASE] =
                "EVENT_DATA_CONNECTION_METEREDNESS_CHANGED";
        sCmdToString[EVENT_NR_FREQUENCY_CHANGED - BASE] = "EVENT_NR_FREQUENCY_CHANGED";
    }
    // Convert cmd to string or null if unknown
    static String cmdToString(int cmd) {
        String value = null;
        cmd -= BASE;
        if ((cmd >= 0) && (cmd < sCmdToString.length)) {
            value = sCmdToString[cmd];
        }
        if (value == null) {
            value = "0x" + Integer.toHexString(cmd + BASE);
        }
        return value;
    }

    /**
     * Create the connection object
     *
     * @param phone the Phone
     * @param id the connection id
     * @return DataConnection that was created.
     */
    public static DataConnection makeDataConnection(Phone phone, int id, DcTracker dct,
                                                    DataServiceManager dataServiceManager,
                                                    DcTesterFailBringUpAll failBringUpAll,
                                                    DcController dcc) {
        String transportType = (dataServiceManager.getTransportType()
                == AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                ? "C"   // Cellular
                : "I";  // IWLAN
        DataConnection dc = new DataConnection(phone, transportType + "-"
                + mInstanceNumber.incrementAndGet(), id, dct, dataServiceManager, failBringUpAll,
                dcc);
        dc.start();
        if (DBG) dc.log("Made " + dc.getName());
        return dc;
    }

    protected void dispose() {
        log("dispose: call quiteNow()");
        quitNow();
    }

    /* Getter functions */

    LinkProperties getLinkProperties() {
        return new LinkProperties(mLinkProperties);
    }

    boolean isInactive() {
        return getCurrentState() == mInactiveState;
    }

    boolean isDisconnecting() {
        return getCurrentState() == mDisconnectingState;
    }

    boolean isActive() {
        return getCurrentState() == mActiveState;
    }

    boolean isActivating() {
        return getCurrentState() == mActivatingState;
    }

    boolean hasBeenTransferred() {
        return mHandoverState == HANDOVER_STATE_COMPLETED;
    }

    boolean isBeingInTransferring() {
        return mHandoverState == HANDOVER_STATE_BEING_TRANSFERRED;
    }

    int getCid() {
        return mCid;
    }

    ApnSetting getApnSetting() {
        return mApnSetting;
    }

    void setLinkPropertiesHttpProxy(ProxyInfo proxy) {
        mLinkProperties.setHttpProxy(proxy);
    }

    public static class UpdateLinkPropertyResult {
        public SetupResult setupResult = SetupResult.SUCCESS;
        public LinkProperties oldLp;
        public LinkProperties newLp;
        public UpdateLinkPropertyResult(LinkProperties curLp) {
            oldLp = curLp;
            newLp = curLp;
        }
    }

    /**
     * Class returned by onSetupConnectionCompleted.
     */
    public enum SetupResult {
        SUCCESS,
        ERROR_RADIO_NOT_AVAILABLE,
        ERROR_INVALID_ARG,
        ERROR_STALE,
        ERROR_DATA_SERVICE_SPECIFIC_ERROR;

        public int mFailCause;

        SetupResult() {
            mFailCause = DataFailCause.getFailCause(0);
        }

        @Override
        public String toString() {
            return name() + "  SetupResult.mFailCause=" + mFailCause;
        }
    }

    public boolean isIpv4Connected() {
        boolean ret = false;
        Collection <InetAddress> addresses = mLinkProperties.getAddresses();

        for (InetAddress addr: addresses) {
            if (addr instanceof java.net.Inet4Address) {
                java.net.Inet4Address i4addr = (java.net.Inet4Address) addr;
                if (!i4addr.isAnyLocalAddress() && !i4addr.isLinkLocalAddress() &&
                        !i4addr.isLoopbackAddress() && !i4addr.isMulticastAddress()) {
                    ret = true;
                    break;
                }
            }
        }
        return ret;
    }

    public boolean isIpv6Connected() {
        boolean ret = false;
        Collection <InetAddress> addresses = mLinkProperties.getAddresses();

        for (InetAddress addr: addresses) {
            if (addr instanceof java.net.Inet6Address) {
                java.net.Inet6Address i6addr = (java.net.Inet6Address) addr;
                if (!i6addr.isAnyLocalAddress() && !i6addr.isLinkLocalAddress() &&
                        !i6addr.isLoopbackAddress() && !i6addr.isMulticastAddress()) {
                    ret = true;
                    break;
                }
            }
        }
        return ret;
    }

    @VisibleForTesting
    public UpdateLinkPropertyResult updateLinkProperty(DataCallResponse newState) {
        UpdateLinkPropertyResult result = new UpdateLinkPropertyResult(mLinkProperties);

        if (newState == null) return result;

        result.newLp = new LinkProperties();

        // set link properties based on data call response
        result.setupResult = setLinkProperties(newState, result.newLp);
        if (result.setupResult != SetupResult.SUCCESS) {
            if (DBG) log("updateLinkProperty failed : " + result.setupResult);
            return result;
        }
        // copy HTTP proxy as it is not part DataCallResponse.
        result.newLp.setHttpProxy(mLinkProperties.getHttpProxy());

        checkSetMtu(mApnSetting, result.newLp);

        mLinkProperties = result.newLp;

        updateTcpBufferSizes(mRilRat);

        if (DBG && (! result.oldLp.equals(result.newLp))) {
            log("updateLinkProperty old LP=" + result.oldLp);
            log("updateLinkProperty new LP=" + result.newLp);
        }

        if (result.newLp.equals(result.oldLp) == false &&
                mNetworkAgent != null) {
            mNetworkAgent.sendLinkProperties(mLinkProperties, DataConnection.this);
        }

        return result;
    }

    /**
     * Read the MTU value from link properties where it can be set from network. In case
     * not set by the network, set it again using the mtu szie value defined in the APN
     * database for the connected APN
     */
    private void checkSetMtu(ApnSetting apn, LinkProperties lp) {
        if (lp == null) return;

        if (apn == null || lp == null) return;

        if (lp.getMtu() != PhoneConstants.UNSET_MTU) {
            if (DBG) log("MTU set by call response to: " + lp.getMtu());
            return;
        }

        if (apn != null && apn.getMtu() != PhoneConstants.UNSET_MTU) {
            lp.setMtu(apn.getMtu());
            if (DBG) log("MTU set by APN to: " + apn.getMtu());
            return;
        }

        int mtu = mPhone.getContext().getResources().getInteger(
                com.android.internal.R.integer.config_mobile_mtu);
        if (mtu != PhoneConstants.UNSET_MTU) {
            lp.setMtu(mtu);
            if (DBG) log("MTU set by config resource to: " + mtu);
        }
    }

    private boolean isApnTypeDefault() {
        final String[] types = ApnSetting.getApnTypesStringFromBitmask(
            mApnSetting.getApnTypeBitmask()).split(",");
        for (String type : types) {
            if (type.equals(PhoneConstants.APN_TYPE_DEFAULT)) {
                return true;
            } else {
                continue;
            }
        }
        return false;
    }

    //***** Constructor (NOTE: uses dcc.getHandler() as its Handler)
    protected DataConnection(Phone phone, String tagSuffix, int id,
                           DcTracker dct, DataServiceManager dataServiceManager,
                           DcTesterFailBringUpAll failBringUpAll, DcController dcc) {
        super("DC-" + tagSuffix, dcc.getHandler());
        mTagSuffix = tagSuffix;
        setLogRecSize(300);
        setLogOnlyTransitions(true);
        if (DBG) log("DataConnection created");

        mPhone = phone;
        mDct = dct;
        mDataServiceManager = dataServiceManager;
        mTransportType = dataServiceManager.getTransportType();
        mDcTesterFailBringUpAll = failBringUpAll;
        mDcController = dcc;
        mId = id;
        mCid = -1;
        ServiceState ss = mPhone.getServiceState();
        mDataRegState = mPhone.getServiceState().getDataRegState();
        int networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;

        NetworkRegistrationInfo nri = ss.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_PS, mTransportType);
        if (nri != null) {
            networkType = nri.getAccessNetworkTechnology();
            mRilRat = ServiceState.networkTypeToRilRadioTechnology(networkType);
        }

        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_MOBILE,
                networkType, NETWORK_TYPE, TelephonyManager.getNetworkTypeName(networkType));
        mNetworkInfo.setRoaming(ss.getDataRoaming());
        mNetworkInfo.setIsAvailable(true);

        addState(mDefaultState);
            addState(mInactiveState, mDefaultState);
            addState(mActivatingState, mDefaultState);
            addState(mActiveState, mDefaultState);
            addState(mDisconnectingState, mDefaultState);
            addState(mDisconnectingErrorCreatingConnection, mDefaultState);
        setInitialState(mInactiveState);
    }

    /**
     * Get the source transport for handover. For example, handover from WWAN to WLAN, WWAN is the
     * source transport, and vice versa.
     */
    private @TransportType int getHandoverSourceTransport() {
        return mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN
                ? AccessNetworkConstants.TRANSPORT_TYPE_WLAN
                : AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
    }

    /**
     * Begin setting up a data connection, calls setupDataCall
     * and the ConnectionParams will be returned with the
     * EVENT_SETUP_DATA_CONNECTION_DONE
     *
     * @param cp is the connection parameters
     *
     * @return Fail cause if failed to setup data connection. {@link DataFailCause#NONE} if success.
     */
    private @DataFailCause.FailCause int connect(ConnectionParams cp) {
        log("connect: carrier='" + mApnSetting.getEntryName()
                + "' APN='" + mApnSetting.getApnName()
                + "' proxy='" + mApnSetting.getProxyAddressAsString()
                + "' port='" + mApnSetting.getProxyPort() + "'");
        if (cp.mApnContext != null) cp.mApnContext.requestLog("DataConnection.connect");

        // Check if we should fake an error.
        if (mDcTesterFailBringUpAll.getDcFailBringUp().mCounter  > 0) {
            DataCallResponse response = new DataCallResponse(
                    mDcTesterFailBringUpAll.getDcFailBringUp().mFailCause,
                    mDcTesterFailBringUpAll.getDcFailBringUp().mSuggestedRetryTime, 0, 0, 0, "",
                    null, null, null, null, PhoneConstants.UNSET_MTU);

            Message msg = obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE, cp);
            AsyncResult.forMessage(msg, response, null);
            sendMessage(msg);
            if (DBG) {
                log("connect: FailBringUpAll=" + mDcTesterFailBringUpAll.getDcFailBringUp()
                        + " send error response=" + response);
            }
            mDcTesterFailBringUpAll.getDcFailBringUp().mCounter -= 1;
            return DataFailCause.NONE;
        }

        mCreateTime = -1;
        mLastFailTime = -1;
        mLastFailCause = DataFailCause.NONE;

        Message msg = obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE, cp);
        msg.obj = cp;

        DataProfile dp =
                DcTracker.createDataProfile(mApnSetting, cp.mProfileId, cp.mIsApnPreferred);

        // We need to use the actual modem roaming state instead of the framework roaming state
        // here. This flag is only passed down to ril_service for picking the correct protocol (for
        // old modem backward compatibility).
        boolean isModemRoaming = mPhone.getServiceState().getDataRoamingFromRegistration();

        // Set this flag to true if the user turns on data roaming. Or if we override the roaming
        // state in framework, we should set this flag to true as well so the modem will not reject
        // the data call setup (because the modem actually thinks the device is roaming).
        boolean allowRoaming = mPhone.getDataRoamingEnabled()
                || (isModemRoaming && !mPhone.getServiceState().getDataRoaming());

        // Check if this data setup is a handover.
        LinkProperties linkProperties = null;
        int reason = DataService.REQUEST_REASON_NORMAL;
        if (cp.mRequestType == DcTracker.REQUEST_TYPE_HANDOVER) {
            // If this is a data setup for handover, we need to pass the link properties
            // of the existing data connection to the modem.
            DcTracker dcTracker = mPhone.getDcTracker(getHandoverSourceTransport());
            if (dcTracker == null || cp.mApnContext == null) {
                loge("connect: Handover failed. dcTracker=" + dcTracker + ", apnContext="
                        + cp.mApnContext);
                return DataFailCause.HANDOVER_FAILED;
            }

            DataConnection dc = dcTracker.getDataConnectionByApnType(cp.mApnContext.getApnType());
            if (dc == null) {
                loge("connect: Can't find data connection for handover.");
                return DataFailCause.HANDOVER_FAILED;
            }

            linkProperties = dc.getLinkProperties();
            // Preserve the potential network agent from the source data connection. The ownership
            // is not transferred at this moment.
            mHandoverLocalLog.log("Handover started. Preserved the agent.");
            mHandoverSourceNetworkAgent = dc.getNetworkAgent();
            log("Get the handover source network agent: " + mHandoverSourceNetworkAgent);
            dc.setHandoverState(HANDOVER_STATE_BEING_TRANSFERRED);
            if (linkProperties == null) {
                loge("connect: Can't find link properties of handover data connection. dc="
                        + dc);
                return DataFailCause.HANDOVER_FAILED;
            }

            reason = DataService.REQUEST_REASON_HANDOVER;
        }

        mDataServiceManager.setupDataCall(
                ServiceState.rilRadioTechnologyToAccessNetworkType(cp.mRilRat),
                dp,
                isModemRoaming,
                allowRoaming,
                reason,
                linkProperties,
                msg);
        TelephonyMetrics.getInstance().writeSetupDataCall(mPhone.getPhoneId(), cp.mRilRat,
                dp.getProfileId(), dp.getApn(), dp.getProtocolType());
        return DataFailCause.NONE;
    }

    public void onSubscriptionOverride(int overrideMask, int overrideValue) {
        mSubscriptionOverride = (mSubscriptionOverride & ~overrideMask)
                | (overrideValue & overrideMask);
        sendMessage(obtainMessage(EVENT_DATA_CONNECTION_OVERRIDE_CHANGED));
    }

    /**
     * Update NetworkCapabilities.NET_CAPABILITY_NOT_METERED based on meteredness
     * @param isUnmetered whether this DC should be set to unmetered or not
     */
    public void onMeterednessChanged(boolean isUnmetered) {
        sendMessage(obtainMessage(EVENT_DATA_CONNECTION_METEREDNESS_CHANGED, isUnmetered));
    }

    /**
     * TearDown the data connection when the deactivation is complete a Message with
     * msg.what == EVENT_DEACTIVATE_DONE
     *
     * @param o is the object returned in the AsyncResult.obj.
     */
    private void tearDownData(Object o) {
        int discReason = DataService.REQUEST_REASON_NORMAL;
        ApnContext apnContext = null;
        if ((o != null) && (o instanceof DisconnectParams)) {
            DisconnectParams dp = (DisconnectParams) o;
            apnContext = dp.mApnContext;
            if (TextUtils.equals(dp.mReason, Phone.REASON_RADIO_TURNED_OFF)
                    || TextUtils.equals(dp.mReason, Phone.REASON_PDP_RESET)) {
                discReason = DataService.REQUEST_REASON_SHUTDOWN;
            } else if (dp.mReleaseType == DcTracker.RELEASE_TYPE_HANDOVER) {
                discReason = DataService.REQUEST_REASON_HANDOVER;
            }
        }

        String str = "tearDownData. mCid=" + mCid + ", reason=" + discReason;
        if (DBG) log(str);
        if (apnContext != null) apnContext.requestLog(str);
        mDataServiceManager.deactivateDataCall(mCid, discReason,
                obtainMessage(EVENT_DEACTIVATE_DONE, mTag, 0, o));
    }

    private void notifyAllWithEvent(ApnContext alreadySent, int event, String reason) {
        mNetworkInfo.setDetailedState(mNetworkInfo.getDetailedState(), reason,
                mNetworkInfo.getExtraInfo());
        for (ConnectionParams cp : mApnContexts.values()) {
            ApnContext apnContext = cp.mApnContext;
            if (apnContext == alreadySent) continue;
            if (reason != null) apnContext.setReason(reason);
            Pair<ApnContext, Integer> pair = new Pair<>(apnContext, cp.mConnectionGeneration);
            Message msg = mDct.obtainMessage(event, mCid, cp.mRequestType, pair);
            AsyncResult.forMessage(msg);
            msg.sendToTarget();
        }
    }

    /**
     * Send the connectionCompletedMsg.
     *
     * @param cp is the ConnectionParams
     * @param cause and if no error the cause is DataFailCause.NONE
     * @param sendAll is true if all contexts are to be notified
     */
    private void notifyConnectCompleted(ConnectionParams cp, @DataFailCause.FailCause int cause,
                                        boolean sendAll) {
        ApnContext alreadySent = null;

        if (cp != null && cp.mOnCompletedMsg != null) {
            // Get the completed message but only use it once
            Message connectionCompletedMsg = cp.mOnCompletedMsg;
            cp.mOnCompletedMsg = null;
            alreadySent = cp.mApnContext;

            long timeStamp = System.currentTimeMillis();
            connectionCompletedMsg.arg1 = mCid;
            connectionCompletedMsg.arg2 = cp.mRequestType;

            if (cause == DataFailCause.NONE) {
                mCreateTime = timeStamp;
                AsyncResult.forMessage(connectionCompletedMsg);
            } else {
                mLastFailCause = cause;
                mLastFailTime = timeStamp;

                // Return message with a Throwable exception to signify an error.
                if (cause == DataFailCause.NONE) cause = DataFailCause.UNKNOWN;
                AsyncResult.forMessage(connectionCompletedMsg, cause,
                        new Throwable(DataFailCause.toString(cause)));
            }
            if (DBG) {
                log("notifyConnectCompleted at " + timeStamp + " cause=" + cause
                        + " connectionCompletedMsg=" + msgToString(connectionCompletedMsg));
            }

            connectionCompletedMsg.sendToTarget();
        }
        if (sendAll && !(isPdpRejectConfigEnabled() && isPdpRejectCause(cause))) {
            log("Send to all. " + alreadySent + " " + DataFailCause.toString(cause));
            notifyAllWithEvent(alreadySent, DctConstants.EVENT_DATA_SETUP_COMPLETE_ERROR,
                    DataFailCause.toString(cause));
        }
    }

    /**
     * Send ar.userObj if its a message, which is should be back to originator.
     *
     * @param dp is the DisconnectParams.
     */
    protected void notifyDisconnectCompleted(DisconnectParams dp, boolean sendAll) {
        if (VDBG) log("NotifyDisconnectCompleted");

        ApnContext alreadySent = null;
        String reason = null;

        if (dp != null && dp.mOnCompletedMsg != null) {
            // Get the completed message but only use it once
            Message msg = dp.mOnCompletedMsg;
            dp.mOnCompletedMsg = null;
            if (msg.obj instanceof ApnContext) {
                alreadySent = (ApnContext)msg.obj;
            }
            reason = dp.mReason;
            if (VDBG) {
                log(String.format("msg=%s msg.obj=%s", msg.toString(),
                    ((msg.obj instanceof String) ? (String) msg.obj : "<no-reason>")));
            }
            AsyncResult.forMessage(msg);
            msg.sendToTarget();
        }
        if (sendAll) {
            if (reason == null) {
                reason = DataFailCause.toString(DataFailCause.UNKNOWN);
            }
            notifyAllWithEvent(alreadySent, DctConstants.EVENT_DISCONNECT_DONE, reason);
        }
        if (DBG) log("NotifyDisconnectCompleted DisconnectParams=" + dp);
    }

    /*
     * **************************************************************************
     * Begin Members and methods owned by DataConnectionTracker but stored
     * in a DataConnection because there is one per connection.
     * **************************************************************************
     */

    /*
     * The id is owned by DataConnectionTracker.
     */
    private int mId;

    /**
     * Get the DataConnection ID
     */
    public int getDataConnectionId() {
        return mId;
    }

    /*
     * **************************************************************************
     * End members owned by DataConnectionTracker
     * **************************************************************************
     */

    /**
     * Clear all settings called when entering mInactiveState.
     */
    private void clearSettings() {
        if (DBG) log("clearSettings");

        mCreateTime = -1;
        mLastFailTime = -1;
        mLastFailCause = DataFailCause.NONE;
        mCid = -1;

        mPcscfAddr = new String[5];

        mLinkProperties = new LinkProperties();
        mApnContexts.clear();
        mApnSetting = null;
        mUnmeteredUseOnly = false;
        mRestrictedNetworkOverride = false;
        mDcFailCause = DataFailCause.NONE;
        mDisabledApnTypeBitMask = 0;
        mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        mSubscriptionOverride = 0;
        mUnmeteredOverride = false;
    }

    /**
     * Process setup data completion result from data service
     *
     * @param resultCode The result code returned by data service
     * @param response Data call setup response from data service
     * @param cp The original connection params used for data call setup
     * @return Setup result
     */
    private SetupResult onSetupConnectionCompleted(@DataServiceCallback.ResultCode int resultCode,
                                                   DataCallResponse response,
                                                   ConnectionParams cp) {
        SetupResult result;

        log("onSetupConnectionCompleted: resultCode=" + resultCode + ", response=" + response);
        if (cp.mTag != mTag) {
            if (DBG) {
                log("onSetupConnectionCompleted stale cp.tag=" + cp.mTag + ", mtag=" + mTag);
            }
            result = SetupResult.ERROR_STALE;
        } else if (resultCode == DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE) {
            result = SetupResult.ERROR_RADIO_NOT_AVAILABLE;
            result.mFailCause = DataFailCause.RADIO_NOT_AVAILABLE;
        } else if (response.getCause() != 0) {
            if (response.getCause() == DataFailCause.RADIO_NOT_AVAILABLE) {
                result = SetupResult.ERROR_RADIO_NOT_AVAILABLE;
                result.mFailCause = DataFailCause.RADIO_NOT_AVAILABLE;
            } else {
                result = SetupResult.ERROR_DATA_SERVICE_SPECIFIC_ERROR;
                result.mFailCause = DataFailCause.getFailCause(response.getCause());
            }
        } else {
            if (DBG) log("onSetupConnectionCompleted received successful DataCallResponse");
            mCid = response.getId();

            mPcscfAddr = response.getPcscfAddresses().stream()
                    .map(InetAddress::getHostAddress).toArray(String[]::new);

            result = updateLinkProperty(response).setupResult;
        }

        return result;
    }

    private boolean isDnsOk(String[] domainNameServers) {
        if (NULL_IP.equals(domainNameServers[0]) && NULL_IP.equals(domainNameServers[1])
                && !mPhone.isDnsCheckDisabled()) {
            // Work around a race condition where QMI does not fill in DNS:
            // Deactivate PDP and let DataConnectionTracker retry.
            // Do not apply the race condition workaround for MMS APN
            // if Proxy is an IP-address.
            // Otherwise, the default APN will not be restored anymore.
            if (!isIpAddress(mApnSetting.getMmsProxyAddressAsString())) {
                log(String.format(
                        "isDnsOk: return false apn.types=%d APN_TYPE_MMS=%s isIpAddress(%s)=%s",
                        mApnSetting.getApnTypeBitmask(), PhoneConstants.APN_TYPE_MMS,
                        mApnSetting.getMmsProxyAddressAsString(),
                        isIpAddress(mApnSetting.getMmsProxyAddressAsString())));
                return false;
            }
        }
        return true;
    }

    /**
     * TCP buffer size config based on the ril technology. There are 6 parameters
     * read_min, read_default, read_max, write_min, write_default, write_max in the TCP buffer
     * config string and they are separated by a comma. The unit of these parameters is byte.
     */
    private static final String TCP_BUFFER_SIZES_GPRS = "4092,8760,48000,4096,8760,48000";
    private static final String TCP_BUFFER_SIZES_EDGE = "4093,26280,70800,4096,16384,70800";
    private static final String TCP_BUFFER_SIZES_UMTS = "58254,349525,1048576,58254,349525,1048576";
    private static final String TCP_BUFFER_SIZES_1XRTT = "16384,32768,131072,4096,16384,102400";
    private static final String TCP_BUFFER_SIZES_EVDO = "4094,87380,262144,4096,16384,262144";
    private static final String TCP_BUFFER_SIZES_EHRPD = "131072,262144,1048576,4096,16384,524288";
    private static final String TCP_BUFFER_SIZES_HSDPA = "61167,367002,1101005,8738,52429,262114";
    private static final String TCP_BUFFER_SIZES_HSPA = "40778,244668,734003,16777,100663,301990";
    private static final String TCP_BUFFER_SIZES_LTE =
            "524288,1048576,2097152,262144,524288,1048576";
    private static final String TCP_BUFFER_SIZES_HSPAP =
            "122334,734003,2202010,32040,192239,576717";
    private static final String TCP_BUFFER_SIZES_NR =
            "2097152,6291456,16777216,512000,2097152,8388608";
    private static final String TCP_BUFFER_SIZES_LTE_CA =
            "4096,6291456,12582912,4096,1048576,2097152";

    private void updateTcpBufferSizes(int rilRat) {
        String sizes = null;
        ServiceState ss = mPhone.getServiceState();
        if (rilRat == ServiceState.RIL_RADIO_TECHNOLOGY_LTE &&
                ss.isUsingCarrierAggregation()) {
            rilRat = ServiceState.RIL_RADIO_TECHNOLOGY_LTE_CA;
        }
        String ratName = ServiceState.rilRadioTechnologyToString(rilRat).toLowerCase(Locale.ROOT);
        // ServiceState gives slightly different names for EVDO tech ("evdo-rev.0" for ex)
        // - patch it up:
        if (rilRat == ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_0 ||
                rilRat == ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A ||
                rilRat == ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B) {
            ratName = RAT_NAME_EVDO;
        }

        // NR 5G Non-Standalone use LTE cell as the primary cell, the ril technology is LTE in this
        // case. We use NR 5G TCP buffer size when connected to NR 5G Non-Standalone network.
        if (mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN
                && (
                    rilRat == ServiceState.RIL_RADIO_TECHNOLOGY_LTE 
                    || rilRat == ServiceState.RIL_RADIO_TECHNOLOGY_LTE_CA
                ) && isNRConnected()
                && mPhone.getServiceStateTracker().getNrContextIds().contains(mCid)) {
            ratName = RAT_NAME_5G;
        }

        log("updateTcpBufferSizes: " + ratName);

        // in the form: "ratname:rmem_min,rmem_def,rmem_max,wmem_min,wmem_def,wmem_max"
        String[] configOverride = mPhone.getContext().getResources().getStringArray(
                com.android.internal.R.array.config_mobile_tcp_buffers);
        for (int i = 0; i < configOverride.length; i++) {
            String[] split = configOverride[i].split(":");
            if (ratName.equals(split[0]) && split.length == 2) {
                sizes = split[1];
                break;
            }
        }

        if (sizes == null) {
            // no override - use telephony defaults
            // doing it this way allows device or carrier to just override the types they
            // care about and inherit the defaults for the others.
            switch (rilRat) {
                case ServiceState.RIL_RADIO_TECHNOLOGY_GPRS:
                    sizes = TCP_BUFFER_SIZES_GPRS;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_EDGE:
                    sizes = TCP_BUFFER_SIZES_EDGE;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_UMTS:
                    sizes = TCP_BUFFER_SIZES_UMTS;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_1xRTT:
                    sizes = TCP_BUFFER_SIZES_1XRTT;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_0:
                case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A:
                case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B:
                    sizes = TCP_BUFFER_SIZES_EVDO;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD:
                    sizes = TCP_BUFFER_SIZES_EHRPD;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_HSDPA:
                    sizes = TCP_BUFFER_SIZES_HSDPA;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_HSPA:
                case ServiceState.RIL_RADIO_TECHNOLOGY_HSUPA:
                    sizes = TCP_BUFFER_SIZES_HSPA;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_LTE:
                    // Use NR 5G TCP buffer size when connected to NR 5G Non-Standalone network.
                    if (RAT_NAME_5G.equals(ratName)) {
                        sizes = TCP_BUFFER_SIZES_NR;
                    } else {
                        sizes = TCP_BUFFER_SIZES_LTE;
                    }
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_LTE_CA:
                    // Use NR 5G TCP buffer size when connected to NR 5G Non-Standalone network.
                    if (isNRConnected()) {
                        sizes = TCP_BUFFER_SIZES_NR;
                    } else {
                        sizes = TCP_BUFFER_SIZES_LTE_CA;
                    }
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP:
                    sizes = TCP_BUFFER_SIZES_HSPAP;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_NR:
                    sizes = TCP_BUFFER_SIZES_NR;
                    break;
                default:
                    // Leave empty - this will let ConnectivityService use the system default.
                    break;
            }
        }
        mLinkProperties.setTcpBufferSizes(sizes);
    }

    private void updateLinkBandwidths(NetworkCapabilities caps, int rilRat) {
        String ratName = ServiceState.rilRadioTechnologyToString(rilRat);
        if (rilRat == ServiceState.RIL_RADIO_TECHNOLOGY_LTE && isNRConnected()) {
            ratName = mPhone.getServiceState().getNrFrequencyRange()
                    == ServiceState.FREQUENCY_RANGE_MMWAVE
                    ? DctConstants.RAT_NAME_NR_NSA_MMWAVE : DctConstants.RAT_NAME_NR_NSA;
        }

        if (DBG) log("updateLinkBandwidths: " + ratName);

        Pair<Integer, Integer> values = mDct.getLinkBandwidths(ratName);
        if (values == null) {
            values = new Pair<>(14, 14);
        }
        caps.setLinkDownstreamBandwidthKbps(values.first);
        caps.setLinkUpstreamBandwidthKbps(values.second);
    }

    /**
     * Indicates if this data connection was established for unmetered use only. Note that this
     * flag should be populated when data becomes active. And if it is set to true, it can be set to
     * false later when we are reevaluating the data connection. But if it is set to false, it
     * can never become true later because setting it to true will cause this data connection
     * losing some immutable network capabilities, which can cause issues in connectivity service.
     */
    private boolean mUnmeteredUseOnly = false;

    /**
     * Indicates if when this connection was established we had a restricted/privileged
     * NetworkRequest and needed it to overcome data-enabled limitations.
     *
     * This flag overrides the APN-based restriction capability, restricting the network
     * based on both having a NetworkRequest with restricted AND needing a restricted
     * bit to overcome user-disabled status.  This allows us to handle the common case
     * of having both restricted requests and unrestricted requests for the same apn:
     * if conditions require a restricted network to overcome user-disabled then it must
     * be restricted, otherwise it is unrestricted (or restricted based on APN type).
     *
     * This supports a privileged app bringing up a network without general apps having access
     * to it when the network is otherwise unavailable (hipri).  The first use case is
     * pre-paid SIM reprovisioning over internet, where the carrier insists on no traffic
     * other than from the privileged carrier-app.
     *
     * Note that the data connection cannot go from unrestricted to restricted because the
     * connectivity service does not support dynamically closing TCP connections at this point.
     */
    private boolean mRestrictedNetworkOverride = false;

    /**
     * Check if this data connection should be restricted. We should call this when data connection
     * becomes active, or when we want to re-evaluate the conditions to decide if we need to
     * unstrict the data connection.
     *
     * @return True if this data connection needs to be restricted.
     */

    private boolean shouldRestrictNetwork() {
        // first, check if there is any network request that containing restricted capability
        // (i.e. Do not have NET_CAPABILITY_NOT_RESTRICTED in the request)
        boolean isAnyRestrictedRequest = false;
        for (ApnContext apnContext : mApnContexts.keySet()) {
            if (apnContext.hasRestrictedRequests(true /* exclude DUN */)) {
                isAnyRestrictedRequest = true;
                break;
            }
        }

        // If all of the network requests are non-restricted, then we don't need to restrict
        // the network.
        if (!isAnyRestrictedRequest) {
            return false;
        }

        // If the network is unmetered, then we don't need to restrict the network because users
        // won't be charged anyway.
        if (!ApnSettingUtils.isMetered(mApnSetting, mPhone)) {
            return false;
        }

        // If the data is disabled, then we need to restrict the network so only privileged apps can
        // use the restricted network while data is disabled.
        if (!mPhone.getDataEnabledSettings().isDataEnabled()) {
            return true;
        }

        // If the device is roaming, and the user does not turn on data roaming, then we need to
        // restrict the network so only privileged apps can use it.
        if (!mDct.getDataRoamingEnabled() && mPhone.getServiceState().getDataRoaming()) {
            return true;
        }

        // Otherwise we should not restrict the network so anyone who requests can use it.
        return false;
    }

    /**
     * @return True if this data connection should only be used for unmetered purposes.
     */
    private boolean isUnmeteredUseOnly() {
        // If this data connection is on IWLAN, then it's unmetered and can be used by everyone.
        // Should not be for unmetered used only.
        if (mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN) {
            return false;
        }

        // If data is enabled, this data connection can't be for unmetered used only because
        // everyone should be able to use it.
        if (mPhone.getDataEnabledSettings().isDataEnabled()) {
            return false;
        }

        // If the device is roaming and data roaming it turned on, then this data connection can't
        // be for unmetered use only.
        if (mDct.getDataRoamingEnabled() && mPhone.getServiceState().getDataRoaming()) {
            return false;
        }

        // The data connection can only be unmetered used only if all attached APN contexts
        // attached to this data connection are unmetered.
        for (ApnContext apnContext : mApnContexts.keySet()) {
            if (ApnSettingUtils.isMeteredApnType(apnContext.getApnTypeBitmask(), mPhone)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the {@link NetworkCapabilities} of this data connection.
     */
    public NetworkCapabilities getNetworkCapabilities() {
        NetworkCapabilities result = new NetworkCapabilities();
        result.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);

        if (mApnSetting != null) {
            final String[] types = ApnSetting.getApnTypesStringFromBitmask(
                mApnSetting.getApnTypeBitmask() & ~mDisabledApnTypeBitMask).split(",");
            for (String type : types) {
                if (!mRestrictedNetworkOverride && mUnmeteredUseOnly
                        && ApnSettingUtils.isMeteredApnType(
                                ApnSetting.getApnTypesBitmaskFromString(type), mPhone)) {
                    log("Dropped the metered " + type + " for the unmetered data call.");
                    continue;
                }
                switch (type) {
                    case PhoneConstants.APN_TYPE_ALL: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_FOTA);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_CBS);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_IA);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_DEFAULT: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_MMS: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_SUPL: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_DUN: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_FOTA: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_FOTA);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_IMS: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_CBS: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_CBS);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_IA: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_IA);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_EMERGENCY: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_EIMS);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_MCX: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_MCX);
                        break;
                    }
                    default:
                }
            }

            // Mark NOT_METERED in the following cases,
            // 1. All APNs in APN settings are unmetered.
            // 2. The non-restricted data and is intended for unmetered use only.
            if ((mUnmeteredUseOnly && !mRestrictedNetworkOverride)
                    || !ApnSettingUtils.isMetered(mApnSetting, mPhone)) {
                result.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            } else {
                result.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            }

            result.maybeMarkCapabilitiesRestricted();
        }

        if (mRestrictedNetworkOverride) {
            result.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
            // don't use dun on restriction-overriden networks.
            result.removeCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
        }

        updateLinkBandwidths(result, mRilRat);

        result.setNetworkSpecifier(new StringNetworkSpecifier(Integer.toString(mSubId)));

        result.setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING,
                !mPhone.getServiceState().getDataRoaming());

        result.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED);

        // Override values set above when requested by policy
        if ((mSubscriptionOverride & OVERRIDE_UNMETERED) != 0) {
            result.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }
        if ((mSubscriptionOverride & OVERRIDE_CONGESTED) != 0) {
            result.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED);
        }

        // Override set by DcTracker
        if (mUnmeteredOverride) {
            result.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }

        return result;
    }

    /**
     * @return {@code True} if 464xlat should be skipped.
     */
    @VisibleForTesting
    public boolean shouldSkip464Xlat() {
        switch (mApnSetting.getSkip464Xlat()) {
            case Telephony.Carriers.SKIP_464XLAT_ENABLE:
                return true;
            case Telephony.Carriers.SKIP_464XLAT_DISABLE:
                return false;
            case Telephony.Carriers.SKIP_464XLAT_DEFAULT:
            default:
                break;
        }

        // As default, return true if ims and no internet
        final NetworkCapabilities nc = getNetworkCapabilities();
        return nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                && !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /**
     * @return {@code} true iff. {@code address} is a literal IPv4 or IPv6 address.
     */
    @VisibleForTesting
    public static boolean isIpAddress(String address) {
        if (address == null) return false;

        return InetAddress.isNumeric(address);
    }

    private SetupResult setLinkProperties(DataCallResponse response,
            LinkProperties linkProperties) {
        // Check if system property dns usable
        String propertyPrefix = "net." + response.getInterfaceName() + ".";
        String dnsServers[] = new String[2];
        dnsServers[0] = SystemProperties.get(propertyPrefix + "dns1");
        dnsServers[1] = SystemProperties.get(propertyPrefix + "dns2");
        boolean okToUseSystemPropertyDns = isDnsOk(dnsServers);

        SetupResult result;

        // Start with clean network properties and if we have
        // a failure we'll clear again at the bottom of this code.
        linkProperties.clear();

        if (response.getCause() == DataFailCause.NONE) {
            try {
                // set interface name
                linkProperties.setInterfaceName(response.getInterfaceName());

                // set link addresses
                if (response.getAddresses().size() > 0) {
                    for (LinkAddress la : response.getAddresses()) {
                        if (!la.getAddress().isAnyLocalAddress()) {
                            if (DBG) {
                                log("addr/pl=" + la.getAddress() + "/"
                                        + la.getNetworkPrefixLength());
                            }
                            linkProperties.addLinkAddress(la);
                        }
                    }
                } else {
                    throw new UnknownHostException("no address for ifname="
                            + response.getInterfaceName());
                }

                // set dns servers
                if (response.getDnsAddresses().size() > 0) {
                    for (InetAddress dns : response.getDnsAddresses()) {
                        if (!dns.isAnyLocalAddress()) {
                            linkProperties.addDnsServer(dns);
                        }
                    }
                } else if (okToUseSystemPropertyDns) {
                    for (String dnsAddr : dnsServers) {
                        dnsAddr = dnsAddr.trim();
                        if (dnsAddr.isEmpty()) continue;
                        InetAddress ia;
                        try {
                            ia = NetworkUtils.numericToInetAddress(dnsAddr);
                        } catch (IllegalArgumentException e) {
                            throw new UnknownHostException("Non-numeric dns addr=" + dnsAddr);
                        }
                        if (!ia.isAnyLocalAddress()) {
                            linkProperties.addDnsServer(ia);
                        }
                    }
                } else {
                    throw new UnknownHostException("Empty dns response and no system default dns");
                }

                // set pcscf
                if (response.getPcscfAddresses().size() > 0) {
                    for (InetAddress pcscf : response.getPcscfAddresses()) {
                        linkProperties.addPcscfServer(pcscf);
                    }
                }

                for (InetAddress gateway : response.getGatewayAddresses()) {
                    // Allow 0.0.0.0 or :: as a gateway;
                    // this indicates a point-to-point interface.
                    linkProperties.addRoute(new RouteInfo(gateway));
                }

                // set interface MTU
                // this may clobber the setting read from the APN db, but that's ok
                linkProperties.setMtu(response.getMtu());

                result = SetupResult.SUCCESS;
            } catch (UnknownHostException e) {
                log("setLinkProperties: UnknownHostException " + e);
                result = SetupResult.ERROR_INVALID_ARG;
            }
        } else {
            result = SetupResult.ERROR_DATA_SERVICE_SPECIFIC_ERROR;
        }

        // An error occurred so clear properties
        if (result != SetupResult.SUCCESS) {
            if (DBG) {
                log("setLinkProperties: error clearing LinkProperties status="
                        + response.getCause() + " result=" + result);
            }
            linkProperties.clear();
        }

        return result;
    }

    /**
     * Initialize connection, this will fail if the
     * apnSettings are not compatible.
     *
     * @param cp the Connection parameters
     * @return true if initialization was successful.
     */
    private boolean initConnection(ConnectionParams cp) {
        ApnContext apnContext = cp.mApnContext;
        if (mApnSetting == null) {
            // Only change apn setting if it isn't set, it will
            // only NOT be set only if we're in DcInactiveState.
            mApnSetting = apnContext.getApnSetting();
        }
        if (mApnSetting == null || !mApnSetting.canHandleType(apnContext.getApnTypeBitmask())) {
            if (DBG) {
                log("initConnection: incompatible apnSetting in ConnectionParams cp=" + cp
                        + " dc=" + DataConnection.this);
            }
            return false;
        }
        mTag += 1;
        mConnectionParams = cp;
        mConnectionParams.mTag = mTag;

        // always update the ConnectionParams with the latest or the
        // connectionGeneration gets stale
        mApnContexts.put(apnContext, cp);

        if (DBG) {
            log("initConnection: "
                    + " RefCount=" + mApnContexts.size()
                    + " mApnList=" + mApnContexts
                    + " mConnectionParams=" + mConnectionParams);
        }
        return true;
    }

    /**
     * The parent state for all other states.
     */
    private class DcDefaultState extends State {
        @Override
        public void enter() {
            if (DBG) log("DcDefaultState: enter");

            // Register for DRS or RAT change
            mPhone.getServiceStateTracker().registerForDataRegStateOrRatChanged(
                    mTransportType, getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED, null);

            mPhone.getServiceStateTracker().registerForDataRoamingOn(getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_ROAM_ON, null);
            mPhone.getServiceStateTracker().registerForDataRoamingOff(getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_ROAM_OFF, null, true);
            mPhone.getServiceStateTracker().registerForNrStateChanged(getHandler(),
                    DataConnection.EVENT_NR_STATE_CHANGED, null);
            mPhone.getServiceStateTracker().registerForNrFrequencyChanged(getHandler(),
                    DataConnection.EVENT_NR_FREQUENCY_CHANGED, null);

            // Add ourselves to the list of data connections
            mDcController.addDc(DataConnection.this);
        }
        @Override
        public void exit() {
            if (DBG) log("DcDefaultState: exit");

            // Unregister for DRS or RAT change.
            mPhone.getServiceStateTracker().unregisterForDataRegStateOrRatChanged(
                    mTransportType, getHandler());

            mPhone.getServiceStateTracker().unregisterForDataRoamingOn(getHandler());
            mPhone.getServiceStateTracker().unregisterForDataRoamingOff(getHandler());
            mPhone.getServiceStateTracker().unregisterForNrStateChanged(getHandler());
            mPhone.getServiceStateTracker().unregisterForNrFrequencyChanged(getHandler());

            // Remove ourselves from the DC lists
            mDcController.removeDc(DataConnection.this);

            if (mAc != null) {
                mAc.disconnected();
                mAc = null;
            }
            mApnContexts.clear();
            mReconnectIntent = null;
            mDct = null;
            mApnSetting = null;
            mPhone = null;
            mDataServiceManager = null;
            mLinkProperties = null;
            mLastFailCause = DataFailCause.NONE;
            mUserData = null;
            mDcController = null;
            mDcTesterFailBringUpAll = null;
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal = HANDLED;

            if (VDBG) {
                log("DcDefault msg=" + getWhatToString(msg.what)
                        + " RefCount=" + mApnContexts.size());
            }
            switch (msg.what) {
                case EVENT_RESET:
                    if (VDBG) log("DcDefaultState: msg.what=REQ_RESET");
                    transitionTo(mInactiveState);
                    break;
                case EVENT_CONNECT:
                    if (DBG) log("DcDefaultState: msg.what=EVENT_CONNECT, fail not expected");
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    notifyConnectCompleted(cp, DataFailCause.UNKNOWN, false);
                    break;

                case EVENT_DISCONNECT:
                case EVENT_DISCONNECT_ALL:
                case EVENT_REEVALUATE_RESTRICTED_STATE:
                    if (DBG) {
                        log("DcDefaultState deferring msg.what=" + getWhatToString(msg.what)
                                + " RefCount=" + mApnContexts.size());
                    }
                    deferMessage(msg);
                    break;
                case EVENT_TEAR_DOWN_NOW:
                    if (DBG) log("DcDefaultState EVENT_TEAR_DOWN_NOW");
                    mDataServiceManager.deactivateDataCall(mCid, DataService.REQUEST_REASON_NORMAL,
                            null);
                    break;
                case EVENT_LOST_CONNECTION:
                    if (DBG) {
                        String s = "DcDefaultState ignore EVENT_LOST_CONNECTION"
                                + " tag=" + msg.arg1 + ":mTag=" + mTag;
                        logAndAddLogRec(s);
                    }
                    break;
                case EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED:
                    AsyncResult ar = (AsyncResult)msg.obj;
                    Pair<Integer, Integer> drsRatPair = (Pair<Integer, Integer>)ar.result;
                    mDataRegState = drsRatPair.first;
                    updateTcpBufferSizes(drsRatPair.second);
                    mRilRat = drsRatPair.second;
                    if (DBG) {
                        log("DcDefaultState: EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED"
                                + " drs=" + mDataRegState
                                + " mRilRat=" + mRilRat);
                    }
                    updateNetworkInfo();
                    updateNetworkInfoSuspendState();
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendNetworkCapabilities(getNetworkCapabilities(),
                                DataConnection.this);
                        mNetworkAgent.sendNetworkInfo(mNetworkInfo, DataConnection.this);
                        mNetworkAgent.sendLinkProperties(mLinkProperties, DataConnection.this);
                    }
                    break;
                case EVENT_DATA_CONNECTION_METEREDNESS_CHANGED:
                    boolean isUnmetered = (boolean) msg.obj;
                    if (isUnmetered == mUnmeteredOverride) {
                        break;
                    }
                    mUnmeteredOverride = isUnmetered;
                    // fallthrough
                case EVENT_NR_FREQUENCY_CHANGED:
                case EVENT_DATA_CONNECTION_ROAM_ON:
                case EVENT_DATA_CONNECTION_ROAM_OFF:
                case EVENT_DATA_CONNECTION_OVERRIDE_CHANGED:
                    updateNetworkInfo();
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendNetworkCapabilities(getNetworkCapabilities(),
                                DataConnection.this);
                        mNetworkAgent.sendNetworkInfo(mNetworkInfo, DataConnection.this);
                    }
                    break;
                case EVENT_KEEPALIVE_START_REQUEST:
                case EVENT_KEEPALIVE_STOP_REQUEST:
                    if (mNetworkAgent != null) {
                        mNetworkAgent.onSocketKeepaliveEvent(
                                msg.arg1, SocketKeepalive.ERROR_INVALID_NETWORK);
                    }
                    break;
                case EVENT_RETRY_CONNECTION:
                    if (DBG) {
                        String s = "DcDefaultState ignore EVENT_RETRY_CONNECTION"
                                + " tag=" + msg.arg1 + ":mTag=" + mTag;
                        logAndAddLogRec(s);
                    }
                    break;

                default:
                    if (DBG) {
                        log("DcDefaultState: ignore msg.what=" + getWhatToString(msg.what));
                    }
                    break;
            }

            return retVal;
        }
    }

    private void updateNetworkInfo() {
        final ServiceState state = mPhone.getServiceState();
        final int subtype = state.getDataNetworkType();
        mNetworkInfo.setSubtype(subtype, TelephonyManager.getNetworkTypeName(subtype));
        mNetworkInfo.setRoaming(state.getDataRoaming());
    }

    private void updateNetworkInfoSuspendState() {
        // this is only called when we are either connected or suspended.  Decide which.
        if (mNetworkAgent == null) {
            Rlog.e(getName(), "Setting suspend state without a NetworkAgent");
        }

        // if we are not in-service change to SUSPENDED
        final ServiceStateTracker sst = mPhone.getServiceStateTracker();
        if (sst.getCurrentDataConnectionState() != ServiceState.STATE_IN_SERVICE) {
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.SUSPENDED, null,
                    mNetworkInfo.getExtraInfo());
        } else {
            // check for voice call and concurrency issues
            if (sst.isConcurrentVoiceAndDataAllowed() == false) {
                final CallTracker ct = mPhone.getCallTracker();
                if (ct.getState() != PhoneConstants.State.IDLE) {
                    mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.SUSPENDED, null,
                            mNetworkInfo.getExtraInfo());
                    return;
                }
            }
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null,
                    mNetworkInfo.getExtraInfo());
        }
    }

    private DcDefaultState mDefaultState = new DcDefaultState();

    /**
     * The state machine is inactive and expects a EVENT_CONNECT.
     */
    protected class DcInactiveState extends State {
        // Inform all contexts we've failed connecting
        public void setEnterNotificationParams(ConnectionParams cp,
                                               @DataFailCause.FailCause int cause) {
            if (VDBG) log("DcInactiveState: setEnterNotificationParams cp,cause");
            mConnectionParams = cp;
            mDisconnectParams = null;
            mDcFailCause = cause;
        }

        // Inform all contexts we've failed disconnected
        public void setEnterNotificationParams(DisconnectParams dp) {
            if (VDBG) log("DcInactiveState: setEnterNotificationParams dp");
            mConnectionParams = null;
            mDisconnectParams = dp;
            mDcFailCause = DataFailCause.NONE;
        }

        // Inform all contexts of the failure cause
        public void setEnterNotificationParams(@DataFailCause.FailCause int cause) {
            mConnectionParams = null;
            mDisconnectParams = null;
            mDcFailCause = cause;
        }

        @Override
        public void enter() {
            mTag += 1;
            if (DBG) log("DcInactiveState: enter() mTag=" + mTag);
            StatsLog.write(StatsLog.MOBILE_CONNECTION_STATE_CHANGED,
                    StatsLog.MOBILE_CONNECTION_STATE_CHANGED__STATE__INACTIVE,
                    mPhone.getPhoneId(), mId,
                    mApnSetting != null ? (long) mApnSetting.getApnTypeBitmask() : 0L,
                    mApnSetting != null
                        ? mApnSetting.canHandleType(ApnSetting.TYPE_DEFAULT) : false);
            if (mHandoverState == HANDOVER_STATE_BEING_TRANSFERRED) {
                mHandoverState = HANDOVER_STATE_COMPLETED;
            }

            // Check for dangling agent. Ideally the handover source agent should be null if
            // handover process is smooth. When it's not null, that means handover failed. The
            // agent was not successfully transferred to the new data connection. We should
            // gracefully notify connectivity service the network was disconnected.
            if (mHandoverSourceNetworkAgent != null) {
                DataConnection sourceDc = mHandoverSourceNetworkAgent.getDataConnection();
                if (sourceDc != null) {
                    // If the source data connection still owns this agent, then just reset the
                    // handover state back to idle because handover is already failed.
                    mHandoverLocalLog.log(
                            "Handover failed. Reset the source dc state to idle");
                    sourceDc.setHandoverState(HANDOVER_STATE_IDLE);
                } else {
                    // The agent is now a dangling agent. No data connection owns this agent.
                    // Gracefully notify connectivity service disconnected.
                    mHandoverLocalLog.log(
                            "Handover failed and dangling agent found.");
                    mHandoverSourceNetworkAgent.acquireOwnership(
                            DataConnection.this, mTransportType);
                    mHandoverSourceNetworkAgent.sendNetworkInfo(mNetworkInfo, DataConnection.this);
                    mHandoverSourceNetworkAgent.releaseOwnership(DataConnection.this);
                }
                mHandoverSourceNetworkAgent = null;
            }

            if (mConnectionParams != null) {
                if (DBG) {
                    log("DcInactiveState: enter notifyConnectCompleted +ALL failCause="
                            + mDcFailCause);
                }
                notifyConnectCompleted(mConnectionParams, mDcFailCause, true);
            }
            if (mDisconnectParams != null) {
                if (DBG) {
                    log("DcInactiveState: enter notifyDisconnectCompleted +ALL failCause="
                            + mDcFailCause);
                }
                notifyDisconnectCompleted(mDisconnectParams, true);
            }
            if (mDisconnectParams == null && mConnectionParams == null
                    && mDcFailCause != DataFailCause.NONE) {
                if (DBG) {
                    log("DcInactiveState: enter notifyAllDisconnectCompleted failCause="
                            + mDcFailCause);
                }
                notifyAllWithEvent(null, DctConstants.EVENT_DISCONNECT_DONE,
                        DataFailCause.toString(mDcFailCause));
            }

            // Remove ourselves from cid mapping, before clearSettings
            mDcController.removeActiveDcByCid(DataConnection.this);

            if (!(isPdpRejectConfigEnabled() && isPdpRejectCause(mDcFailCause))) {
                log("DcInactiveState: clearing settings");
                clearSettings();
            }
        }

        @Override
        public void exit() {
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_RESET:
                case EVENT_REEVALUATE_RESTRICTED_STATE:
                    if (DBG) {
                        log("DcInactiveState: msg.what=" + getWhatToString(msg.what)
                                + ", ignore we're already done");
                    }
                    return HANDLED;
                case EVENT_CONNECT:
                    if (DBG) log("DcInactiveState: mag.what=EVENT_CONNECT");

                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    if (isPdpRejectConfigEnabled() && !isDataCallConnectAllowed()) {
                        if (DBG) log("DcInactiveState: skip EVENT_CONNECT");
                        cp.mApnContext.decAndGetConnectionGeneration();
                        return HANDLED;
                    }

                    if (!initConnection(cp)) {
                        log("DcInactiveState: msg.what=EVENT_CONNECT initConnection failed");
                        notifyConnectCompleted(cp, DataFailCause.UNACCEPTABLE_NETWORK_PARAMETER,
                                false);
                        transitionTo(mInactiveState);
                        return HANDLED;
                    }

                    int cause = connect(cp);
                    if (cause != DataFailCause.NONE) {
                        log("DcInactiveState: msg.what=EVENT_CONNECT connect failed");
                        notifyConnectCompleted(cp, cause, false);
                        transitionTo(mInactiveState);
                        return HANDLED;
                    }

                    if (mSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        mSubId = cp.mSubId;
                    }

                    transitionTo(mActivatingState);
                    return HANDLED;
                case EVENT_DISCONNECT:
                    if (DBG) log("DcInactiveState: msg.what=EVENT_DISCONNECT");
                    notifyDisconnectCompleted((DisconnectParams)msg.obj, false);
                    return HANDLED;
                case EVENT_DISCONNECT_ALL:
                    if (DBG) log("DcInactiveState: msg.what=EVENT_DISCONNECT_ALL");
                    notifyDisconnectCompleted((DisconnectParams)msg.obj, false);
                    return HANDLED;
                case EVENT_RETRY_CONNECTION:
                    if (DBG) {
                        log("DcInactiveState: msg.what=EVENT_RETRY_CONNECTION"
                                + " mConnectionParams=" + mConnectionParams);
                    }
                    if (mConnectionParams != null) {
                        if (initConnection(mConnectionParams)) {
                            //In case of apn modify, get the latest apn to retry
                            mApnSetting = mConnectionParams.mApnContext.getApnSetting();
                            connect(mConnectionParams);
                            transitionTo(mActivatingState);
                        } else {
                            if (DBG) {
                                log("DcInactiveState: msg.what=EVENT_RETRY_CONNECTION"
                                    + " initConnection failed");
                            }
                        }
                    }
                    return HANDLED;
                default:
                    if (VDBG) {
                        log("DcInactiveState not handled msg.what=" + getWhatToString(msg.what));
                    }
                    return NOT_HANDLED;
            }
        }
    }
    protected DcInactiveState mInactiveState = new DcInactiveState();

    /**
     * The state machine is activating a connection.
     */
    private class DcActivatingState extends State {
        @Override
        public void enter() {
            StatsLog.write(StatsLog.MOBILE_CONNECTION_STATE_CHANGED,
                    StatsLog.MOBILE_CONNECTION_STATE_CHANGED__STATE__ACTIVATING,
                    mPhone.getPhoneId(), mId,
                    mApnSetting != null ? (long) mApnSetting.getApnTypeBitmask() : 0L,
                    mApnSetting != null
                        ? mApnSetting.canHandleType(ApnSetting.TYPE_DEFAULT) : false);
            setHandoverState(HANDOVER_STATE_IDLE);
            // restricted evaluation depends on network requests from apnContext. The evaluation
            // should happen once entering connecting state rather than active state because it's
            // possible that restricted network request can be released during the connecting window
            // and if we wait for connection established, then we might mistakenly
            // consider it as un-restricted. ConnectivityService then will immediately
            // tear down the connection through networkAgent unwanted callback if all requests for
            // this connection are going away.
            mRestrictedNetworkOverride = shouldRestrictNetwork();
        }
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;
            AsyncResult ar;
            ConnectionParams cp;

            if (DBG) log("DcActivatingState: msg=" + msgToString(msg));
            switch (msg.what) {
                case EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED:
                case EVENT_CONNECT:
                    // Activating can't process until we're done.
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;

                case EVENT_SETUP_DATA_CONNECTION_DONE:
                    cp = (ConnectionParams) msg.obj;

                    DataCallResponse dataCallResponse =
                            msg.getData().getParcelable(DataServiceManager.DATA_CALL_RESPONSE);
                    SetupResult result = onSetupConnectionCompleted(msg.arg1, dataCallResponse, cp);
                    if (result != null) {
                        log("EVENT_SETUP_DATA_CONNECTION_DONE, result: " + result
                                + ", mFailCause: " + result.mFailCause);
                    }
                    if (result != SetupResult.ERROR_STALE) {
                        if (mConnectionParams != cp) {
                            loge("DcActivatingState: WEIRD mConnectionsParams:"+ mConnectionParams
                                    + " != cp:" + cp);
                        }
                    }
                    if (DBG) {
                        log("DcActivatingState onSetupConnectionCompleted result=" + result
                                + " dc=" + DataConnection.this);
                    }
                    if (cp.mApnContext != null) {
                        cp.mApnContext.requestLog("onSetupConnectionCompleted result=" + result);
                    }
                    switch (result) {
                        case SUCCESS:
                            // All is well
                            mDcFailCause = DataFailCause.NONE;
                            transitionTo(mActiveState);
                            handlePdpRejectCauseSuccess();
                            break;
                        case ERROR_RADIO_NOT_AVAILABLE:
                            // Vendor ril rejected the command and didn't connect.
                            // Transition to inactive but send notifications after
                            // we've entered the mInactive state.
                            mInactiveState.setEnterNotificationParams(cp, result.mFailCause);
                            transitionTo(mInactiveState);
                            break;
                        case ERROR_INVALID_ARG:
                            // The addresses given from the RIL are bad
                            tearDownData(cp);
                            transitionTo(mDisconnectingErrorCreatingConnection);
                            break;
                        case ERROR_DATA_SERVICE_SPECIFIC_ERROR:

                            // Retrieve the suggested retry delay from the modem and save it.
                            // If the modem want us to retry the current APN again, it will
                            // suggest a positive delay value (in milliseconds). Otherwise we'll get
                            // NO_SUGGESTED_RETRY_DELAY here.

                            long delay = getSuggestedRetryDelay(dataCallResponse);
                            cp.mApnContext.setModemSuggestedDelay(delay);

                            String str = "DcActivatingState: ERROR_DATA_SERVICE_SPECIFIC_ERROR "
                                    + " delay=" + delay
                                    + " result=" + result
                                    + " result.isRadioRestartFailure="
                                    + DataFailCause.isRadioRestartFailure(mPhone.getContext(),
                                    result.mFailCause, mPhone.getSubId())
                                    + " isPermanentFailure=" +
                                    mDct.isPermanentFailure(result.mFailCause);
                            if (DBG) log(str);
                            if (isPdpRejectCauseFailureHandled(result, cp)) {
                                if (DBG) log("isPdpRejectCauseFailureHandled true, breaking");
                                break;
                            }
                            if (cp.mApnContext != null) cp.mApnContext.requestLog(str);

                            // Save the cause. DcTracker.onDataSetupComplete will check this
                            // failure cause and determine if we need to retry this APN later
                            // or not.
                            mInactiveState.setEnterNotificationParams(cp, result.mFailCause);
                            transitionTo(mInactiveState);
                            break;
                        case ERROR_STALE:
                            loge("DcActivatingState: stale EVENT_SETUP_DATA_CONNECTION_DONE"
                                    + " tag:" + cp.mTag + " != mTag:" + mTag);
                            break;
                        default:
                            throw new RuntimeException("Unknown SetupResult, should not happen");
                    }
                    retVal = HANDLED;
                    break;
                default:
                    if (VDBG) {
                        log("DcActivatingState not handled msg.what=" +
                                getWhatToString(msg.what) + " RefCount=" + mApnContexts.size());
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcActivatingState mActivatingState = new DcActivatingState();

    /**
     * The state machine is connected, expecting an EVENT_DISCONNECT.
     */
    private class DcActiveState extends State {

        @Override public void enter() {
            if (DBG) log("DcActiveState: enter dc=" + DataConnection.this);
            StatsLog.write(StatsLog.MOBILE_CONNECTION_STATE_CHANGED,
                    StatsLog.MOBILE_CONNECTION_STATE_CHANGED__STATE__ACTIVE,
                    mPhone.getPhoneId(), mId,
                    mApnSetting != null ? (long) mApnSetting.getApnTypeBitmask() : 0L,
                    mApnSetting != null
                        ? mApnSetting.canHandleType(ApnSetting.TYPE_DEFAULT) : false);

            updateNetworkInfo();

            // If we were retrying there maybe more than one, otherwise they'll only be one.
            notifyAllWithEvent(null, DctConstants.EVENT_DATA_SETUP_COMPLETE,
                    Phone.REASON_CONNECTED);

            mPhone.getCallTracker().registerForVoiceCallStarted(getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_VOICE_CALL_STARTED, null);
            mPhone.getCallTracker().registerForVoiceCallEnded(getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_VOICE_CALL_ENDED, null);

            // If the EVENT_CONNECT set the current max retry restore it here
            // if it didn't then this is effectively a NOP.
            mDcController.addActiveDcByCid(DataConnection.this);

            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED,
                    mNetworkInfo.getReason(), null);
            mNetworkInfo.setExtraInfo(mApnSetting.getApnName());
            updateTcpBufferSizes(mRilRat);

            final NetworkMisc misc = new NetworkMisc();
            final CarrierSignalAgent carrierSignalAgent = mPhone.getCarrierSignalAgent();
            if (carrierSignalAgent.hasRegisteredReceivers(TelephonyIntents
                    .ACTION_CARRIER_SIGNAL_REDIRECTED)) {
                // carrierSignal Receivers will place the carrier-specific provisioning notification
                misc.provisioningNotificationDisabled = true;
            }
            misc.subscriberId = mPhone.getSubscriberId();

            // set skip464xlat if it is not default otherwise
            misc.skip464xlat = shouldSkip464Xlat();

            mUnmeteredUseOnly = isUnmeteredUseOnly();

            if (DBG) {
                log("mRestrictedNetworkOverride = " + mRestrictedNetworkOverride
                        + ", mUnmeteredUseOnly = " + mUnmeteredUseOnly);
            }

            if (mConnectionParams != null
                    && mConnectionParams.mRequestType == DcTracker.REQUEST_TYPE_HANDOVER) {
                // If this is a data setup for handover, we need to reuse the existing network agent
                // instead of creating a new one. This should be transparent to connectivity
                // service.
                DcTracker dcTracker = mPhone.getDcTracker(getHandoverSourceTransport());
                DataConnection dc = dcTracker.getDataConnectionByApnType(
                        mConnectionParams.mApnContext.getApnType());
                // Don't move the handover state of the source transport to COMPLETED immediately
                // because of ensuring to send deactivating data call for source transport.

                if (mHandoverSourceNetworkAgent != null) {
                    String logStr = "Transfer network agent successfully.";
                    log(logStr);
                    mHandoverLocalLog.log(logStr);
                    mNetworkAgent = mHandoverSourceNetworkAgent;
                    mNetworkAgent.acquireOwnership(DataConnection.this, mTransportType);

                    // TODO: Should evaluate mDisabledApnTypeBitMask again after handover. We don't
                    // do it now because connectivity service does not support dynamically removing
                    // immutable capabilities.

                    // Update the capability after handover
                    mNetworkAgent.sendNetworkCapabilities(getNetworkCapabilities(),
                            DataConnection.this);
                    mNetworkAgent.sendLinkProperties(mLinkProperties, DataConnection.this);
                    mHandoverSourceNetworkAgent = null;
                } else if (dc != null) {
                    logd("Create network agent as source DC did not create it");
                    createDcNetorkAgent(misc);
                } else {
                    String logStr = "Failed to get network agent from original data connection";
                    loge(logStr);
                    mHandoverLocalLog.log(logStr);
                    return;
                }
            } else {
                createDcNetorkAgent(misc);
            }

            if (mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
                mPhone.mCi.registerForNattKeepaliveStatus(
                        getHandler(), DataConnection.EVENT_KEEPALIVE_STATUS, null);
                mPhone.mCi.registerForLceInfo(
                        getHandler(), DataConnection.EVENT_LINK_CAPACITY_CHANGED, null);
            }
            TelephonyMetrics.getInstance().writeRilDataCallEvent(mPhone.getPhoneId(),
                    mCid, mApnSetting.getApnTypeBitmask(), RilDataCall.State.CONNECTED);
        }

        private void createDcNetorkAgent(NetworkMisc misc) {
            mScore = calculateScore();
            final NetworkFactory factory = PhoneFactory.getNetworkFactory(
                    mPhone.getPhoneId());
            final int factorySerialNumber = (null == factory)
                    ? NetworkFactory.SerialNumber.NONE : factory.getSerialNumber();
            mDisabledApnTypeBitMask |= getDisallowedApnTypes();
            mNetworkAgent = DcNetworkAgent.createDcNetworkAgent(DataConnection.this,
                    mPhone, mNetworkInfo, mScore, misc, factorySerialNumber, mTransportType);
        }

        @Override
        public void exit() {
            if (DBG) log("DcActiveState: exit dc=" + this);
            String reason = mNetworkInfo.getReason();
            if(mDcController.isExecutingCarrierChange()) {
                reason = Phone.REASON_CARRIER_CHANGE;
            } else if (mDisconnectParams != null && mDisconnectParams.mReason != null) {
                reason = mDisconnectParams.mReason;
            } else {
                reason = DataFailCause.toString(mDcFailCause);
            }
            mPhone.getCallTracker().unregisterForVoiceCallStarted(getHandler());
            mPhone.getCallTracker().unregisterForVoiceCallEnded(getHandler());

            // If the data connection is being handover to other transport, we should not notify
            // disconnected to connectivity service.
            if (mHandoverState != HANDOVER_STATE_BEING_TRANSFERRED) {
                mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED,
                        reason, mNetworkInfo.getExtraInfo());
            }

            if (mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
                mPhone.mCi.unregisterForNattKeepaliveStatus(getHandler());
                mPhone.mCi.unregisterForLceInfo(getHandler());
            }

            // If we are still owning this agent, then we should inform connectivity service the
            // data connection is disconnected. If we don't own this agent at this point, that means
            // it has been transferred to the new data connection for IWLAN data handover case.
            if (mNetworkAgent != null) {
                mNetworkAgent.sendNetworkInfo(mNetworkInfo, DataConnection.this);
                mNetworkAgent.releaseOwnership(DataConnection.this);
            }
            mNetworkAgent = null;

            TelephonyMetrics.getInstance().writeRilDataCallEvent(mPhone.getPhoneId(),
                    mCid, mApnSetting.getApnTypeBitmask(), RilDataCall.State.DISCONNECTED);
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_CONNECT: {
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    // either add this new apn context to our set or
                    // update the existing cp with the latest connection generation number
                    mApnContexts.put(cp.mApnContext, cp);
                    // TODO (b/118347948): evaluate if it's still needed after assigning
                    // different scores to different Cellular network.
                    mDisabledApnTypeBitMask &= ~cp.mApnContext.getApnTypeBitmask();
                    mNetworkAgent.sendNetworkCapabilities(getNetworkCapabilities(),
                            DataConnection.this);
                    if (DBG) {
                        log("DcActiveState: EVENT_CONNECT cp=" + cp + " dc=" + DataConnection.this);
                    }
                    notifyConnectCompleted(cp, DataFailCause.NONE, false);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DISCONNECT: {
                    DisconnectParams dp = (DisconnectParams) msg.obj;
                    if (DBG) {
                        log("DcActiveState: EVENT_DISCONNECT dp=" + dp
                                + " dc=" + DataConnection.this);
                    }
                    if (mApnContexts.containsKey(dp.mApnContext)) {
                        if (DBG) {
                            log("DcActiveState msg.what=EVENT_DISCONNECT RefCount="
                                    + mApnContexts.size());
                        }

                        if (mApnContexts.size() == 1) {
                            mApnContexts.clear();
                            mDisconnectParams = dp;
                            mConnectionParams = null;
                            dp.mTag = mTag;
                            tearDownData(dp);
                            transitionTo(mDisconnectingState);
                        } else {
                            mApnContexts.remove(dp.mApnContext);
                            // TODO (b/118347948): evaluate if it's still needed after assigning
                            // different scores to different Cellular network.
                            mDisabledApnTypeBitMask |= dp.mApnContext.getApnTypeBitmask();
                            mNetworkAgent.sendNetworkCapabilities(getNetworkCapabilities(),
                                    DataConnection.this);
                            notifyDisconnectCompleted(dp, false);
                        }
                    } else {
                        log("DcActiveState ERROR no such apnContext=" + dp.mApnContext
                                + " in this dc=" + DataConnection.this);
                        notifyDisconnectCompleted(dp, false);
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DISCONNECT_ALL: {
                    if (DBG) {
                        log("DcActiveState EVENT_DISCONNECT clearing apn contexts,"
                                + " dc=" + DataConnection.this);
                    }
                    DisconnectParams dp = (DisconnectParams) msg.obj;
                    mDisconnectParams = dp;
                    mConnectionParams = null;
                    dp.mTag = mTag;
                    tearDownData(dp);
                    transitionTo(mDisconnectingState);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_LOST_CONNECTION: {
                    if (DBG) {
                        log("DcActiveState EVENT_LOST_CONNECTION dc=" + DataConnection.this);
                    }

                    mInactiveState.setEnterNotificationParams(DataFailCause.LOST_CONNECTION);
                    transitionTo(mInactiveState);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DATA_CONNECTION_METEREDNESS_CHANGED:
                    boolean isUnmetered = (boolean) msg.obj;
                    if (isUnmetered == mUnmeteredOverride) {
                        retVal = HANDLED;
                        break;
                    }
                    mUnmeteredOverride = isUnmetered;
                    // fallthrough
                case EVENT_DATA_CONNECTION_ROAM_ON:
                case EVENT_DATA_CONNECTION_ROAM_OFF:
                case EVENT_DATA_CONNECTION_OVERRIDE_CHANGED: {
                    updateNetworkInfo();
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendNetworkCapabilities(getNetworkCapabilities(),
                                DataConnection.this);
                        mNetworkAgent.sendNetworkInfo(mNetworkInfo, DataConnection.this);
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_BW_REFRESH_RESPONSE: {
                    AsyncResult ar = (AsyncResult)msg.obj;
                    if (ar.exception != null) {
                        log("EVENT_BW_REFRESH_RESPONSE: error ignoring, e=" + ar.exception);
                    } else {
                        boolean useModem = DctConstants.BANDWIDTH_SOURCE_MODEM_KEY.equals(
                                mPhone.getContext().getResources().getString(com.android.internal.R
                                        .string.config_bandwidthEstimateSource));
                        NetworkCapabilities nc = getNetworkCapabilities();
                        LinkCapacityEstimate lce = (LinkCapacityEstimate) ar.result;
                        if (useModem && mPhone.getLceStatus() == RILConstants.LCE_ACTIVE) {
                            if (lce.downlinkCapacityKbps != LinkCapacityEstimate.INVALID) {
                                nc.setLinkDownstreamBandwidthKbps(lce.downlinkCapacityKbps);
                            }
                            if (lce.uplinkCapacityKbps != LinkCapacityEstimate.INVALID) {
                                nc.setLinkUpstreamBandwidthKbps(lce.uplinkCapacityKbps);
                            }
                        }
                        if (mNetworkAgent != null) {
                            mNetworkAgent.sendNetworkCapabilities(nc, DataConnection.this);
                        }
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DATA_CONNECTION_VOICE_CALL_STARTED:
                case EVENT_DATA_CONNECTION_VOICE_CALL_ENDED: {
                    updateNetworkInfo();
                    updateNetworkInfoSuspendState();
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendNetworkCapabilities(getNetworkCapabilities(),
                                DataConnection.this);
                        mNetworkAgent.sendNetworkInfo(mNetworkInfo, DataConnection.this);
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_KEEPALIVE_START_REQUEST: {
                    KeepalivePacketData pkt = (KeepalivePacketData) msg.obj;
                    int slotId = msg.arg1;
                    int intervalMillis = msg.arg2 * 1000;
                    if (mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
                        mPhone.mCi.startNattKeepalive(
                                DataConnection.this.mCid, pkt, intervalMillis,
                                DataConnection.this.obtainMessage(
                                        EVENT_KEEPALIVE_STARTED, slotId, 0, null));
                    } else {
                        // We currently do not support NATT Keepalive requests using the
                        // DataService API, so unless the request is WWAN (always bound via
                        // the CommandsInterface), the request cannot be honored.
                        //
                        // TODO: b/72331356 to add support for Keepalive to the DataService
                        // so that keepalive requests can be handled (if supported) by the
                        // underlying transport.
                        if (mNetworkAgent != null) {
                            mNetworkAgent.onSocketKeepaliveEvent(
                                    msg.arg1, SocketKeepalive.ERROR_INVALID_NETWORK);
                        }
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_KEEPALIVE_STOP_REQUEST: {
                    int slotId = msg.arg1;
                    int handle = mNetworkAgent.keepaliveTracker.getHandleForSlot(slotId);
                    if (handle < 0) {
                        loge("No slot found for stopSocketKeepalive! " + slotId);
                        retVal = HANDLED;
                        break;
                    } else {
                        logd("Stopping keepalive with handle: " + handle);
                    }

                    mPhone.mCi.stopNattKeepalive(
                            handle, DataConnection.this.obtainMessage(
                                    EVENT_KEEPALIVE_STOPPED, handle, slotId, null));
                    retVal = HANDLED;
                    break;
                }
                case EVENT_KEEPALIVE_STARTED: {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    final int slot = msg.arg1;
                    if (ar.exception != null || ar.result == null) {
                        loge("EVENT_KEEPALIVE_STARTED: error starting keepalive, e="
                                + ar.exception);
                        mNetworkAgent.onSocketKeepaliveEvent(
                                slot, SocketKeepalive.ERROR_HARDWARE_ERROR);
                    } else {
                        KeepaliveStatus ks = (KeepaliveStatus) ar.result;
                        if (ks == null) {
                            loge("Null KeepaliveStatus received!");
                        } else {
                            mNetworkAgent.keepaliveTracker.handleKeepaliveStarted(slot, ks);
                        }
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_KEEPALIVE_STATUS: {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        loge("EVENT_KEEPALIVE_STATUS: error in keepalive, e=" + ar.exception);
                        // We have no way to notify connectivity in this case.
                    }
                    if (ar.result != null) {
                        KeepaliveStatus ks = (KeepaliveStatus) ar.result;
                        mNetworkAgent.keepaliveTracker.handleKeepaliveStatus(ks);
                    }

                    retVal = HANDLED;
                    break;
                }
                case EVENT_KEEPALIVE_STOPPED: {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    final int handle = msg.arg1;
                    final int slotId = msg.arg2;

                    if (ar.exception != null) {
                        loge("EVENT_KEEPALIVE_STOPPED: error stopping keepalive for handle="
                                + handle + " e=" + ar.exception);
                        mNetworkAgent.keepaliveTracker.handleKeepaliveStatus(
                                new KeepaliveStatus(KeepaliveStatus.ERROR_UNKNOWN));
                    } else {
                        log("Keepalive Stop Requested for handle=" + handle);
                        mNetworkAgent.keepaliveTracker.handleKeepaliveStatus(
                                new KeepaliveStatus(handle, KeepaliveStatus.STATUS_INACTIVE));
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_LINK_CAPACITY_CHANGED: {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        loge("EVENT_LINK_CAPACITY_CHANGED e=" + ar.exception);
                    } else {
                        boolean useModem = DctConstants.BANDWIDTH_SOURCE_MODEM_KEY.equals(
                                mPhone.getContext().getResources().getString(com.android.internal.R
                                        .string.config_bandwidthEstimateSource));
                        NetworkCapabilities nc = getNetworkCapabilities();
                        if (useModem) {
                            LinkCapacityEstimate lce = (LinkCapacityEstimate) ar.result;
                            if (lce.downlinkCapacityKbps != LinkCapacityEstimate.INVALID) {
                                nc.setLinkDownstreamBandwidthKbps(lce.downlinkCapacityKbps);
                            }
                            if (lce.uplinkCapacityKbps != LinkCapacityEstimate.INVALID) {
                                nc.setLinkUpstreamBandwidthKbps(lce.uplinkCapacityKbps);
                            }
                        }
                        if (mNetworkAgent != null) {
                            mNetworkAgent.sendNetworkCapabilities(nc, DataConnection.this);
                        }
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_REEVALUATE_RESTRICTED_STATE: {
                    // If the network was restricted, and now it does not need to be restricted
                    // anymore, we should add the NET_CAPABILITY_NOT_RESTRICTED capability.
                    if (mRestrictedNetworkOverride && !shouldRestrictNetwork()) {
                        if (DBG) {
                            log("Data connection becomes not-restricted. dc=" + this);
                        }
                        // Note we only do this when network becomes non-restricted. When a
                        // non-restricted becomes restricted (e.g. users disable data, or turn off
                        // data roaming), DCT will explicitly tear down the networks (because
                        // connectivity service does not support force-close TCP connections today).
                        // Also note that NET_CAPABILITY_NOT_RESTRICTED is an immutable capability
                        // (see {@link NetworkCapabilities}) once we add it to the network, we can't
                        // remove it through the entire life cycle of the connection.
                        mRestrictedNetworkOverride = false;
                        mNetworkAgent.sendNetworkCapabilities(getNetworkCapabilities(),
                                DataConnection.this);
                    }

                    // If the data does need to be unmetered use only (e.g. users turn on data, or
                    // device is not roaming anymore assuming data roaming is off), then we can
                    // dynamically add those metered APN type capabilities back. (But not the
                    // other way around because most of the APN-type capabilities are immutable
                    // capabilities.)
                    if (mUnmeteredUseOnly && !isUnmeteredUseOnly()) {
                        mUnmeteredUseOnly = false;
                        mNetworkAgent.sendNetworkCapabilities(getNetworkCapabilities(),
                                DataConnection.this);
                    }

                    retVal = HANDLED;
                    break;
                }
                case EVENT_REEVALUATE_DATA_CONNECTION_PROPERTIES: {
                    // Update other properties like link properties if needed in future.
                    updateScore();
                    retVal = HANDLED;
                    break;
                }
                case EVENT_NR_STATE_CHANGED: {
                    updateTcpBufferSizes(mRilRat);
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendLinkProperties(mLinkProperties, DataConnection.this);
                    }
                    retVal = HANDLED;
                    break;
                }
                default:
                    if (VDBG) {
                        log("DcActiveState not handled msg.what=" + getWhatToString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcActiveState mActiveState = new DcActiveState();

    /**
     * The state machine is disconnecting.
     */
    private class DcDisconnectingState extends State {
        @Override
        public void enter() {
            StatsLog.write(StatsLog.MOBILE_CONNECTION_STATE_CHANGED,
                    StatsLog.MOBILE_CONNECTION_STATE_CHANGED__STATE__DISCONNECTING,
                    mPhone.getPhoneId(), mId,
                    mApnSetting != null ? (long) mApnSetting.getApnTypeBitmask() : 0L,
                    mApnSetting != null
                        ? mApnSetting.canHandleType(ApnSetting.TYPE_DEFAULT) : false);
        }
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_CONNECT:
                    if (DBG) log("DcDisconnectingState msg.what=EVENT_CONNECT. Defer. RefCount = "
                            + mApnContexts.size());
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;

                case EVENT_DEACTIVATE_DONE:
                    DisconnectParams dp = (DisconnectParams) msg.obj;

                    String str = "DcDisconnectingState msg.what=EVENT_DEACTIVATE_DONE RefCount="
                            + mApnContexts.size();
                    if (DBG) log(str);
                    if (dp.mApnContext != null) dp.mApnContext.requestLog(str);

                    if (dp.mTag == mTag) {
                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.
                        mInactiveState.setEnterNotificationParams(dp);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) log("DcDisconnectState stale EVENT_DEACTIVATE_DONE"
                                + " dp.tag=" + dp.mTag + " mTag=" + mTag);
                    }
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcDisconnectingState not handled msg.what="
                                + getWhatToString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcDisconnectingState mDisconnectingState = new DcDisconnectingState();

    /**
     * The state machine is disconnecting after an creating a connection.
     */
    private class DcDisconnectionErrorCreatingConnection extends State {
        @Override
        public void enter() {
            StatsLog.write(StatsLog.MOBILE_CONNECTION_STATE_CHANGED,
                    StatsLog.MOBILE_CONNECTION_STATE_CHANGED__STATE__DISCONNECTION_ERROR_CREATING_CONNECTION,
                    mPhone.getPhoneId(), mId,
                    mApnSetting != null ? (long) mApnSetting.getApnTypeBitmask() : 0L,
                    mApnSetting != null
                        ? mApnSetting.canHandleType(ApnSetting.TYPE_DEFAULT) : false);
        }
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_DEACTIVATE_DONE:
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    if (cp.mTag == mTag) {
                        String str = "DcDisconnectionErrorCreatingConnection" +
                                " msg.what=EVENT_DEACTIVATE_DONE";
                        if (DBG) log(str);
                        if (cp.mApnContext != null) cp.mApnContext.requestLog(str);

                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.
                        mInactiveState.setEnterNotificationParams(cp,
                                DataFailCause.UNACCEPTABLE_NETWORK_PARAMETER);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) {
                            log("DcDisconnectionErrorCreatingConnection stale EVENT_DEACTIVATE_DONE"
                                    + " dp.tag=" + cp.mTag + ", mTag=" + mTag);
                        }
                    }
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcDisconnectionErrorCreatingConnection not handled msg.what="
                                + getWhatToString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcDisconnectionErrorCreatingConnection mDisconnectingErrorCreatingConnection =
                new DcDisconnectionErrorCreatingConnection();

    /**
     * Bring up a connection to the apn and return an AsyncResult in onCompletedMsg.
     * Used for cellular networks that use Access Point Names (APN) such
     * as GSM networks.
     *
     * @param apnContext is the Access Point Name to bring up a connection to
     * @param profileId for the connection
     * @param rilRadioTechnology Radio technology for the data connection
     * @param onCompletedMsg is sent with its msg.obj as an AsyncResult object.
     *                       With AsyncResult.userObj set to the original msg.obj,
     *                       AsyncResult.result = FailCause and AsyncResult.exception = Exception().
     * @param connectionGeneration used to track a single connection request so disconnects can get
     *                             ignored if obsolete.
     * @param requestType Data request type
     * @param subId the subscription id associated with this data connection.
     * @param isApnPreferred determine if this Apn is preferred.
     */
    public void bringUp(ApnContext apnContext, int profileId, int rilRadioTechnology,
                        Message onCompletedMsg, int connectionGeneration,
                        @RequestNetworkType int requestType, int subId, boolean isApnPreferred) {
        if (DBG) {
            log("bringUp: apnContext=" + apnContext + " onCompletedMsg=" + onCompletedMsg);
        }
        sendMessage(DataConnection.EVENT_CONNECT,
                new ConnectionParams(apnContext, profileId, rilRadioTechnology, onCompletedMsg,
                        connectionGeneration, requestType, subId, isApnPreferred));
    }

    /**
     * Tear down the connection through the apn on the network.
     *
     * @param apnContext APN context
     * @param reason reason to tear down
     * @param onCompletedMsg is sent with its msg.obj as an AsyncResult object.
     *        With AsyncResult.userObj set to the original msg.obj.
     */
    public void tearDown(ApnContext apnContext, String reason, Message onCompletedMsg) {
        if (DBG) {
            log("tearDown: apnContext=" + apnContext + " reason=" + reason + " onCompletedMsg="
                    + onCompletedMsg);
        }
        sendMessage(DataConnection.EVENT_DISCONNECT,
                new DisconnectParams(apnContext, reason, DcTracker.RELEASE_TYPE_DETACH,
                        onCompletedMsg));
    }

    // ******* "public" interface

    /**
     * Used for testing purposes.
     */
    void tearDownNow() {
        if (DBG) log("tearDownNow()");
        sendMessage(obtainMessage(EVENT_TEAR_DOWN_NOW));
    }

    /**
     * Tear down the connection through the apn on the network.  Ignores reference count and
     * and always tears down.
     *
     * @param releaseType Data release type
     * @param onCompletedMsg is sent with its msg.obj as an AsyncResult object.
     *        With AsyncResult.userObj set to the original msg.obj.
     */
    public void tearDownAll(String reason, @ReleaseNetworkType int releaseType,
                            Message onCompletedMsg) {
        if (DBG) log("tearDownAll: reason=" + reason + ", releaseType=" + releaseType);
        sendMessage(DataConnection.EVENT_DISCONNECT_ALL,
                new DisconnectParams(null, reason, releaseType, onCompletedMsg));
    }

    /**
     * Reset the data connection to inactive state.
     */
    public void reset() {
        sendMessage(EVENT_RESET);
        if (DBG) log("reset");
    }

    /**
     * Re-evaluate the restricted state. If the restricted data connection does not need to be
     * restricted anymore, we need to dynamically change the network's capability.
     */
    void reevaluateRestrictedState() {
        sendMessage(EVENT_REEVALUATE_RESTRICTED_STATE);
        if (DBG) log("reevaluate restricted state");
    }

    /**
     * Re-evaluate the data connection properties. For example, it will recalculate data connection
     * score and update through network agent it if changed.
     */
    void reevaluateDataConnectionProperties() {
        sendMessage(EVENT_REEVALUATE_DATA_CONNECTION_PROPERTIES);
        if (DBG) log("reevaluate data connection properties");
    }

    /**
     * @return The parameters used for initiating a data connection.
     */
    public ConnectionParams getConnectionParams() {
        return mConnectionParams;
    }

    /**
     * @return The list of PCSCF addresses
     */
    public String[] getPcscfAddresses() {
        return mPcscfAddr;
    }

    /**
     * Using the result of the SETUP_DATA_CALL determine the retry delay.
     *
     * @param response The response from setup data call
     * @return NO_SUGGESTED_RETRY_DELAY if no retry is needed otherwise the delay to the
     *         next SETUP_DATA_CALL
     */
    private long getSuggestedRetryDelay(DataCallResponse response) {
        /** According to ril.h
         * The value < 0 means no value is suggested
         * The value 0 means retry should be done ASAP.
         * The value of Integer.MAX_VALUE(0x7fffffff) means no retry.
         */

        // The value < 0 means no value is suggested
        if (response.getSuggestedRetryTime() < 0) {
            if (DBG) log("No suggested retry delay.");
            return RetryManager.NO_SUGGESTED_RETRY_DELAY;
        }
        // The value of Integer.MAX_VALUE(0x7fffffff) means no retry.
        else if (response.getSuggestedRetryTime() == Integer.MAX_VALUE) {
            if (DBG) log("Modem suggested not retrying.");
            return RetryManager.NO_RETRY;
        }

        // We need to cast it to long because the value returned from RIL is a 32-bit integer,
        // but the time values used in AlarmManager are all 64-bit long.
        return (long) response.getSuggestedRetryTime();
    }

    public List<ApnContext> getApnContexts() {
        return new ArrayList<>(mApnContexts.keySet());
    }

    /** Get the network agent of the data connection */
    @Nullable
    DcNetworkAgent getNetworkAgent() {
        return mNetworkAgent;
    }

    void setHandoverState(@HandoverState int state) {
        mHandoverLocalLog.log("State changed from " + handoverStateToString(mHandoverState)
                + " to " + handoverStateToString(state));
        mHandoverState = state;
    }

    /**
     * @return the string for msg.what as our info.
     */
    @Override
    protected String getWhatToString(int what) {
        return cmdToString(what);
    }

    private static String msgToString(Message msg) {
        String retVal;
        if (msg == null) {
            retVal = "null";
        } else {
            StringBuilder   b = new StringBuilder();

            b.append("{what=");
            b.append(cmdToString(msg.what));

            b.append(" when=");
            TimeUtils.formatDuration(msg.getWhen() - SystemClock.uptimeMillis(), b);

            if (msg.arg1 != 0) {
                b.append(" arg1=");
                b.append(msg.arg1);
            }

            if (msg.arg2 != 0) {
                b.append(" arg2=");
                b.append(msg.arg2);
            }

            if (msg.obj != null) {
                b.append(" obj=");
                b.append(msg.obj);
            }

            b.append(" target=");
            b.append(msg.getTarget());

            b.append(" replyTo=");
            b.append(msg.replyTo);

            b.append("}");

            retVal = b.toString();
        }
        return retVal;
    }

    static void slog(String s) {
        Rlog.d("DC", s);
    }

    /**
     * Log with debug
     *
     * @param s is string log
     */
    @Override
    protected void log(String s) {
        Rlog.d(getName(), s);
    }

    /**
     * Log with debug attribute
     *
     * @param s is string log
     */
    @Override
    protected void logd(String s) {
        Rlog.d(getName(), s);
    }

    /**
     * Log with verbose attribute
     *
     * @param s is string log
     */
    @Override
    protected void logv(String s) {
        Rlog.v(getName(), s);
    }

    /**
     * Log with info attribute
     *
     * @param s is string log
     */
    @Override
    protected void logi(String s) {
        Rlog.i(getName(), s);
    }

    /**
     * Log with warning attribute
     *
     * @param s is string log
     */
    @Override
    protected void logw(String s) {
        Rlog.w(getName(), s);
    }

    /**
     * Log with error attribute
     *
     * @param s is string log
     */
    @Override
    protected void loge(String s) {
        Rlog.e(getName(), s);
    }

    /**
     * Log with error attribute
     *
     * @param s is string log
     * @param e is a Throwable which logs additional information.
     */
    @Override
    protected void loge(String s, Throwable e) {
        Rlog.e(getName(), s, e);
    }

    /** Doesn't print mApnList of ApnContext's which would be recursive */
    public String toStringSimple() {
        return getName() + ": State=" + getCurrentState().getName()
                + " mApnSetting=" + mApnSetting + " RefCount=" + mApnContexts.size()
                + " mCid=" + mCid + " mCreateTime=" + mCreateTime
                + " mLastastFailTime=" + mLastFailTime
                + " mLastFailCause=" + mLastFailCause
                + " mTag=" + mTag
                + " mLinkProperties=" + mLinkProperties
                + " linkCapabilities=" + getNetworkCapabilities()
                + " mRestrictedNetworkOverride=" + mRestrictedNetworkOverride;
    }

    @Override
    public String toString() {
        return "{" + toStringSimple() + " mApnContexts=" + mApnContexts + "}";
    }

    /** Check if the device is connected to NR 5G Non-Standalone network. */
    private boolean isNRConnected() {
        return mPhone.getServiceState().getNrState()
                == NetworkRegistrationInfo.NR_STATE_CONNECTED;
    }

    /**
     * @return The disallowed APN types bitmask
     */
    private @ApnType int getDisallowedApnTypes() {
        CarrierConfigManager configManager = (CarrierConfigManager)
                mPhone.getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
        int apnTypesBitmask = 0;
        if (configManager != null) {
            PersistableBundle bundle = configManager.getConfigForSubId(mSubId);
            if (bundle != null) {
                String key = (mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        ? CarrierConfigManager.KEY_CARRIER_WWAN_DISALLOWED_APN_TYPES_STRING_ARRAY
                        : CarrierConfigManager.KEY_CARRIER_WLAN_DISALLOWED_APN_TYPES_STRING_ARRAY;
                if (bundle.getStringArray(key) != null) {
                    String disallowedApnTypesString =
                            TextUtils.join(",", bundle.getStringArray(key));
                    if (!TextUtils.isEmpty(disallowedApnTypesString)) {
                        apnTypesBitmask = ApnSetting.getApnTypesBitmaskFromString(
                                disallowedApnTypesString);
                    }
                }
            }
        }

        return apnTypesBitmask;
    }

    private void dumpToLog() {
        dump(null, new PrintWriter(new StringWriter(0)) {
            @Override
            public void println(String s) {
                DataConnection.this.logd(s);
            }

            @Override
            public void flush() {
            }
        }, null);
    }

    /**
     *  Re-calculate score and update through network agent if it changes.
     */
    private void updateScore() {
        int oldScore = mScore;
        mScore = calculateScore();
        if (oldScore != mScore && mNetworkAgent != null) {
            log("Updating score from " + oldScore + " to " + mScore);
            mNetworkAgent.sendNetworkScore(mScore, this);
        }
    }

    private int calculateScore() {
        int score = OTHER_CONNECTION_SCORE;

        // If it's serving a network request that asks NET_CAPABILITY_INTERNET and doesn't have
        // specify a subId, this dataConnection is considered to be default Internet data
        // connection. In this case we assign a slightly higher score of 50. The intention is
        // it will not be replaced by other data connections accidentally in DSDS usecase.
        for (ApnContext apnContext : mApnContexts.keySet()) {
            for (NetworkRequest networkRequest : apnContext.getNetworkRequests()) {
                if (networkRequest.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && networkRequest.networkCapabilities.getNetworkSpecifier() == null) {
                    score = DEFAULT_INTERNET_CONNECTION_SCORE;
                    break;
                }
            }
        }

        return score;
    }

    private String handoverStateToString(@HandoverState int state) {
        switch (state) {
            case HANDOVER_STATE_IDLE: return "IDLE";
            case HANDOVER_STATE_BEING_TRANSFERRED: return "BEING_TRANSFERRED";
            case HANDOVER_STATE_COMPLETED: return "COMPLETED";
            default: return "UNKNOWN";
        }
    }

    /**
     * Dump the current state.
     *
     * @param fd
     * @param pw
     * @param args
     */
    @Override
    public void dump(FileDescriptor fd, PrintWriter printWriter, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, " ");
        pw.print("DataConnection ");
        super.dump(fd, pw, args);
        pw.flush();
        pw.increaseIndent();
        pw.println("transport type="
                + AccessNetworkConstants.transportTypeToString(mTransportType));
        pw.println("mApnContexts.size=" + mApnContexts.size());
        pw.println("mApnContexts=" + mApnContexts);
        pw.println("mApnSetting=" + mApnSetting);
        pw.println("mTag=" + mTag);
        pw.println("mCid=" + mCid);
        pw.println("mConnectionParams=" + mConnectionParams);
        pw.println("mDisconnectParams=" + mDisconnectParams);
        pw.println("mDcFailCause=" + mDcFailCause);
        pw.println("mPhone=" + mPhone);
        pw.println("mSubId=" + mSubId);
        pw.println("mLinkProperties=" + mLinkProperties);
        pw.flush();
        pw.println("mDataRegState=" + mDataRegState);
        pw.println("mHandoverState=" + handoverStateToString(mHandoverState));
        pw.println("mRilRat=" + mRilRat);
        pw.println("mNetworkCapabilities=" + getNetworkCapabilities());
        pw.println("mCreateTime=" + TimeUtils.logTimeOfDay(mCreateTime));
        pw.println("mLastFailTime=" + TimeUtils.logTimeOfDay(mLastFailTime));
        pw.println("mLastFailCause=" + mLastFailCause);
        pw.println("mUserData=" + mUserData);
        pw.println("mSubscriptionOverride=" + Integer.toHexString(mSubscriptionOverride));
        pw.println("mRestrictedNetworkOverride=" + mRestrictedNetworkOverride);
        pw.println("mUnmeteredUseOnly=" + mUnmeteredUseOnly);
        pw.println("disallowedApnTypes="
                + ApnSetting.getApnTypesStringFromBitmask(getDisallowedApnTypes()));
        pw.println("mUnmeteredOverride=" + mUnmeteredOverride);
        pw.println("mInstanceNumber=" + mInstanceNumber);
        pw.println("mAc=" + mAc);
        pw.println("mScore=" + mScore);
        if (mNetworkAgent != null) {
            mNetworkAgent.dump(fd, pw, args);
        }
        pw.println("handover local log:");
        pw.increaseIndent();
        mHandoverLocalLog.dump(fd, pw, args);
        pw.decreaseIndent();
        pw.decreaseIndent();
        pw.println();
        pw.flush();
    }

    protected void handlePdpRejectCauseSuccess() {
        if (DBG) log("DataConnection: handlePdpRejectCauseSuccess()");
    }

    protected boolean isPdpRejectCauseFailureHandled(SetupResult result,
            ConnectionParams cp) {
        if (DBG) log("DataConnection: isPdpRejectCauseFailureHandled()");
        return false;
    }

    protected boolean isPdpRejectCause(int cause) {
        return (cause == DataFailCause.USER_AUTHENTICATION
                || cause == DataFailCause.SERVICE_OPTION_NOT_SUBSCRIBED
                || cause == DataFailCause.MULTI_CONN_TO_SAME_PDN_NOT_ALLOWED);
    }

    protected boolean isPdpRejectConfigEnabled() {
        return mPhone.getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_pdp_retry_for_29_33_55_enabled);
    }

    protected boolean isDataCallConnectAllowed() {
        return true;
    }
}
