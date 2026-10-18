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

import static com.android.internal.telephony.SmsResponse.NO_ERROR_CODE;

import android.annotation.NonNull;
import android.app.PendingIntent;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Message;
import android.os.Process;
import android.os.UserHandle;
import android.telephony.ServiceState;

import com.android.internal.R;
import com.android.internal.telephony.GsmAlphabet.TextEncodingDetails;
import com.android.internal.telephony.InboundSmsHandler;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.SMSDispatcher;
import com.android.internal.telephony.SmsConstants;
import com.android.internal.telephony.SmsController;
import com.android.internal.telephony.SmsDispatchersController;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.SmsMessageBase;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.util.SMSDispatcherUtil;
import com.android.telephony.Rlog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class GsmSMSDispatcher extends SMSDispatcher {
    private static final String TAG = "GsmSMSDispatcher";
    protected UiccController mUiccController = null;
    private AtomicReference<IccRecords> mIccRecords = new AtomicReference<IccRecords>();
    private AtomicReference<UiccCardApplication> mUiccApplication =
            new AtomicReference<UiccCardApplication>();
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private GsmInboundSmsHandler mGsmInboundSmsHandler;

    public GsmSMSDispatcher(Phone phone, SmsDispatchersController smsDispatchersController,
            GsmInboundSmsHandler gsmInboundSmsHandler, @NonNull FeatureFlags featureFlags) {
        super(phone, smsDispatchersController, featureFlags);
        mCi.setOnSmsStatus(this, EVENT_NEW_SMS_STATUS_REPORT, null);
        mGsmInboundSmsHandler = gsmInboundSmsHandler;
        mUiccController = UiccController.getInstance();
        mUiccController.registerForIccChanged(this, EVENT_ICC_CHANGED, null);
        Rlog.d(TAG, "GsmSMSDispatcher created");
    }

    /**
     * Retain the constructor used by MediaTek telephony framework jars built against older APIs.
     */
    public GsmSMSDispatcher(Phone phone, SmsDispatchersController smsDispatchersController,
            GsmInboundSmsHandler gsmInboundSmsHandler) {
        this(phone, smsDispatchersController, gsmInboundSmsHandler,
                smsDispatchersController.getFeatureFlags());
    }

    @Override
    public void dispose() {
        super.dispose();
        mCi.unSetOnSmsStatus(this);
        mUiccController.unregisterForIccChanged(this);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Override
    protected String getFormat() {
        return SmsConstants.FORMAT_3GPP;
    }

    /**
     * Handles 3GPP format-specific events coming from the phone stack.
     * Other events are handled by {@link SMSDispatcher#handleMessage}.
     *
     * @param msg the message to handle
     */
    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_NEW_ICC_SMS:
                // pass to InboundSmsHandler to process
                mGsmInboundSmsHandler.sendMessage(InboundSmsHandler.EVENT_NEW_SMS, msg.obj);
                break;

            case EVENT_ICC_CHANGED:
                onUpdateIccAvailability();
                break;

            default:
                super.handleMessage(msg);
        }
    }

    @Override
    protected boolean shouldBlockSmsForEcbm() {
        // There is no such thing as ECBM for GSM. This only applies to CDMA.
        return false;
    }

    @Override
    protected SmsMessageBase.SubmitPduBase getSubmitPdu(String scAddr, String destAddr,
            String message, boolean statusReportRequested, SmsHeader smsHeader, int priority,
            int validityPeriod) {
        return SMSDispatcherUtil.getSubmitPduGsm(scAddr, destAddr, message, statusReportRequested,
                validityPeriod);
    }

    @Override
    protected SmsMessageBase.SubmitPduBase getSubmitPdu(String scAddr, String destAddr,
            int destPort, byte[] message, boolean statusReportRequested) {
        return SMSDispatcherUtil.getSubmitPduGsm(scAddr, destAddr, destPort, message,
                statusReportRequested);
    }

    @Override
    protected SmsMessageBase.SubmitPduBase getSubmitPdu(String scAddr, String destAddr,
            String message, boolean statusReportRequested, SmsHeader smsHeader, int priority,
            int validityPeriod, int messageRef) {
        return SMSDispatcherUtil.getSubmitPduGsm(scAddr, destAddr, message, statusReportRequested,
                validityPeriod, messageRef);
    }

    @Override
    protected SmsMessageBase.SubmitPduBase getSubmitPdu(String scAddr, String destAddr,
            int destPort, byte[] message, boolean statusReportRequested, int messageRef) {
        return SMSDispatcherUtil.getSubmitPduGsm(scAddr, destAddr, destPort, message,
                statusReportRequested, messageRef);
    }

    @Override
    protected TextEncodingDetails calculateLength(CharSequence messageBody, boolean use7bitOnly) {
        return SMSDispatcherUtil.calculateLengthGsm(messageBody, use7bitOnly);
    }

    // Compatibility hooks used by prebuilt MediaTek telephony framework jars.
    protected SmsMessageBase.SubmitPduBase onSendData(String scAddr, String destAddr,
            int destPort, byte[] data, boolean statusReportRequested, int messageRef) {
        return getSubmitPdu(scAddr, destAddr, destPort, data, statusReportRequested, messageRef);
    }

    protected SmsMessageBase.SubmitPduBase onSendText(String scAddr, String destAddr,
            String text, boolean statusReportRequested, Uri messageUri, String callingPkg,
            boolean persistMessage, int priority, boolean expectMore, int validityPeriod,
            int messageRef) {
        return getSubmitPdu(scAddr, destAddr, text, statusReportRequested, null, priority,
                validityPeriod, messageRef);
    }

    protected int onSendMultipartText(String destAddr, String scAddr, ArrayList<String> parts,
            ArrayList<PendingIntent> sentIntents, ArrayList<PendingIntent> deliveryIntents,
            Uri messageUri, String callingPkg, boolean persistMessage, int priority,
            boolean expectMore, int validityPeriod, TextEncodingDetails[] encodingForParts) {
        int encoding = SmsConstants.ENCODING_UNKNOWN;
        for (int i = 0; i < parts.size(); i++) {
            TextEncodingDetails details = calculateLength(parts.get(i), false);
            if (encoding != details.codeUnitSize
                    && (encoding == SmsConstants.ENCODING_UNKNOWN
                            || encoding == SmsConstants.ENCODING_7BIT)) {
                encoding = details.codeUnitSize;
            }
            encodingForParts[i] = details;
        }
        return encoding;
    }

    protected SmsTracker getSmsTracker(String callingPackage, int callingUser,
            HashMap<String, Object> data, PendingIntent sentIntent,
            PendingIntent deliveryIntent, String format, AtomicInteger unsentPartCount,
            AtomicBoolean anyPartFailed, Uri messageUri, SmsHeader smsHeader,
            boolean expectMore, String fullMessageText, boolean isText, boolean persistMessage,
            int priority, int validityPeriod, boolean isForVvm, long messageId, int messageRef,
            boolean skipShortCodeCheck, long uniqueMessageId) {
        int uid = Process.INVALID_UID;
        if (callingPackage != null) {
            try {
                uid = mContext.createContextAsUser(UserHandle.of(callingUser), 0)
                        .getPackageManager().getPackageUidAsUser(callingPackage, callingUser);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Preserve INVALID_UID if the legacy caller package is unavailable.
            }
        }
        return super.getSmsTracker(callingPackage, callingUser, data, sentIntent, deliveryIntent,
                format, unsentPartCount, anyPartFailed, messageUri, smsHeader, expectMore,
                fullMessageText, isText, persistMessage, priority, validityPeriod, isForVvm,
                messageId, messageRef, skipShortCodeCheck, uniqueMessageId, uid);
    }

    @Override
    protected void handleStatusReport(Object o) {
        if (o instanceof AsyncResult) {
            byte[] pdu = (byte[]) ((AsyncResult) o).result;
            mSmsDispatchersController.handleSmsStatusReport(SmsConstants.FORMAT_3GPP, pdu);
            mCi.acknowledgeLastIncomingGsmSms(true, 0 /* cause */, null);
        } else {
            Rlog.e(TAG, "handleStatusReport() called for object type " + o.getClass().getName());
        }
    }

    /** {@inheritDoc} */
    @UnsupportedAppUsage
    @Override
    protected void sendSms(SmsTracker tracker) {
        int ss = mPhone.getServiceState().getState();

        Rlog.d(TAG, "sendSms: "
                + " isIms()=" + isIms()
                + " mRetryCount=" + tracker.mRetryCount
                + " mImsRetry=" + tracker.mImsRetry
                + " mMessageRef=" + tracker.mMessageRef
                + " mUsesImsServiceForIms=" + tracker.mUsesImsServiceForIms
                + " SS=" + ss
                + " " + SmsController.formatCrossStackMessageId(tracker.mMessageId));

        boolean allowCheckMessageInNotConnected =
                mContext.getResources()
                        .getBoolean(R.bool.config_satellite_allow_check_message_in_not_connected);
        boolean mtPollingMessageThatsAllowedInOOS =
                tracker.isMtSmsPollingMessage(mContext) && allowCheckMessageInNotConnected;
        // if sms over IMS is not supported on data and voice is not available...
        if (!isIms() && ss != ServiceState.STATE_IN_SERVICE && !mtPollingMessageThatsAllowedInOOS) {
        //In 5G case only Data Rat is reported.
            if(mPhone.getServiceState().getRilDataRadioTechnology()
                    != ServiceState.RIL_RADIO_TECHNOLOGY_NR) {
                tracker.onFailed(mContext, getNotInServiceError(ss), NO_ERROR_CODE);
                notifySmsSentFailedToEmergencyStateTracker(tracker, false);
                return;
            }
        }

        Message reply = obtainMessage(EVENT_SEND_SMS_COMPLETE, tracker);
        HashMap<String, Object> map = tracker.getData();
        byte pdu[] = (byte[]) map.get("pdu");
        byte smsc[] = (byte[]) map.get("smsc");
        if (tracker.mRetryCount > 0) {
            // per TS 23.040 Section 9.2.3.6:  If TP-MTI SMS-SUBMIT (0x01) type
            //   TP-RD (bit 2) is 1 for retry
            //   and TP-MR is set to previously failed sms TP-MR
            if (((0x01 & pdu[0]) == 0x01)) {
                pdu[0] |= 0x04; // TP-RD
                pdu[1] = (byte) tracker.mMessageRef; // TP-MR
            }
        }

        // sms over gsm is used:
        //   if sms over IMS is not supported AND
        //   this is not a retry case after sms over IMS failed
        //     indicated by mImsRetry > 0 OR
        //   this tracker uses ImsSmsDispatcher to handle SMS over IMS. This dispatcher has received
        //     this message because the ImsSmsDispatcher has indicated that the message needs to
        //     fall back to sending over CS.
        if (0 == tracker.mImsRetry && !isIms() || tracker.mUsesImsServiceForIms) {
            if (tracker.mRetryCount == 0 && tracker.mExpectMore) {
                mCi.sendSMSExpectMore(IccUtils.bytesToHexString(smsc),
                        IccUtils.bytesToHexString(pdu), reply);
            } else {
                mCi.sendSMS(IccUtils.bytesToHexString(smsc),
                        IccUtils.bytesToHexString(pdu), reply);
            }
        } else {
            mCi.sendImsGsmSms(IccUtils.bytesToHexString(smsc),
                    IccUtils.bytesToHexString(pdu), tracker.mImsRetry,
                    tracker.mMessageRef, reply);
            // increment it here, so in case of SMS_FAIL_RETRY over IMS
            // next retry will be sent using IMS request again.
            tracker.mImsRetry++;
        }
    }

    protected UiccCardApplication getUiccCardApplication() {
            Rlog.d(TAG, "GsmSMSDispatcher: subId = " + mPhone.getSubId()
                    + " slotId = " + mPhone.getPhoneId());
                return mUiccController.getUiccCardApplication(mPhone.getPhoneId(),
                        UiccController.APP_FAM_3GPP);
    }

    private void onUpdateIccAvailability() {
        if (mUiccController == null ) {
            return;
        }

        UiccCardApplication newUiccApplication = getUiccCardApplication();

        UiccCardApplication app = mUiccApplication.get();
        if (app != newUiccApplication) {
            if (app != null) {
                Rlog.d(TAG, "Removing stale icc objects.");
                if (mIccRecords.get() != null) {
                    mIccRecords.get().unregisterForNewSms(this);
                }
                mIccRecords.set(null);
                mUiccApplication.set(null);
            }
            if (newUiccApplication != null) {
                Rlog.d(TAG, "New Uicc application found");
                mUiccApplication.set(newUiccApplication);
                mIccRecords.set(newUiccApplication.getIccRecords());
                if (mIccRecords.get() != null) {
                    mIccRecords.get().registerForNewSms(this, EVENT_NEW_ICC_SMS, null);
                }
            }
        }
    }
}
