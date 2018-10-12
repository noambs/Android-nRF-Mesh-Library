package no.nordicsemi.android.meshprovisioner.meshmessagestates;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import no.nordicsemi.android.meshprovisioner.InternalMeshMsgHandlerCallbacks;
import no.nordicsemi.android.meshprovisioner.InternalTransportCallbacks;
import no.nordicsemi.android.meshprovisioner.MeshStatusCallbacks;
import no.nordicsemi.android.meshprovisioner.control.BlockAcknowledgementMessage;
import no.nordicsemi.android.meshprovisioner.control.TransportControlMessage;
import no.nordicsemi.android.meshprovisioner.messagetypes.ControlMessage;
import no.nordicsemi.android.meshprovisioner.messagetypes.Message;
import no.nordicsemi.android.meshprovisioner.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.meshprovisioner.opcodes.ConfigMessageOpCodes;
import no.nordicsemi.android.meshprovisioner.transport.LowerTransportLayerCallbacks;
import no.nordicsemi.android.meshprovisioner.utils.AddressUtils;
import no.nordicsemi.android.meshprovisioner.utils.MeshParserUtils;

/**
 * This generic class handles the mesh messages received or sent.
 * <p>
 * This class handles sending, resending and parsing mesh messages. Each message sent by the library has its own state.
 * {@link ConfigMessageState} and {@link GenericMessageState} extends this class based on the type of the message.
 * Currently the library supports basic Configuration and Generic Messages.
 * </p>
 */
public abstract class MeshMessageState implements LowerTransportLayerCallbacks {

    private static final String TAG = MeshMessageState.class.getSimpleName();

    protected final Context mContext;
    protected final ProvisionedMeshNode mNode;
    final MeshTransport mMeshTransport;
    final Map<Integer, byte[]> mPayloads = new HashMap<>();
    private final List<Integer> mRetransmitPayloads = new ArrayList<>();
    final byte[] mSrc;
    protected InternalTransportCallbacks mInternalTransportCallbacks;
    protected MeshStatusCallbacks mMeshStatusCallbacks;
    private final InternalMeshMsgHandlerCallbacks meshMessageHandlerCallbacks;
    protected MeshModel mMeshModel;
    protected Message message;
    private boolean isIncompleteTimerExpired;

    MeshMessageState(final Context context, final ProvisionedMeshNode provisionedMeshNode, final InternalMeshMsgHandlerCallbacks callbacks) {
        this.mContext = context;
        this.mNode = provisionedMeshNode;
        this.meshMessageHandlerCallbacks = callbacks;
        this.mSrc = mNode.getConfigurationSrc();
        this.mMeshTransport = new MeshTransport(context, provisionedMeshNode);
        this.mMeshTransport.setLowerTransportLayerCallbacks(this);
    }

    /**
     * Set transport callbacks
     *
     * @param callbacks callbacks
     */
    public void setTransportCallbacks(final InternalTransportCallbacks callbacks) {
        this.mInternalTransportCallbacks = callbacks;
    }

    /**
     * Set mesh status call backs
     *
     * @param callbacks callbacks
     */
    public void setStatusCallbacks(final MeshStatusCallbacks callbacks) {
        this.mMeshStatusCallbacks = callbacks;
    }

    public abstract MessageState getState();

    public final boolean isRetransmissionRequired(final byte[] pdu) {
        parseMeshPdu(pdu);
        return !mRetransmitPayloads.isEmpty();
    }

    /**
     * Starts sending the mesh pdu
     */
    public void executeSend() {
        if (!mPayloads.isEmpty()) {
            for (int i = 0; i < mPayloads.size(); i++) {
                mInternalTransportCallbacks.sendPdu(mNode, mPayloads.get(i));
            }
        }
    }

    /**
     * Re-sends the mesh pdu segments that were lost in flight
     */
    public void executeResend() {
        if (!mPayloads.isEmpty() && !mRetransmitPayloads.isEmpty()) {
            for (int i = 0; i < mRetransmitPayloads.size(); i++) {
                final int segO = mRetransmitPayloads.get(i);
                if (mPayloads.containsKey(segO)) {
                    final byte[] pdu = mPayloads.get(segO);
                    Log.v(TAG, "Resending segment " + segO + " : " + MeshParserUtils.bytesToHex(pdu, false));
                    final Message retransmitMeshMessage = mMeshTransport.createRetransmitMeshMessage(message, segO);
                    mInternalTransportCallbacks.sendPdu(mNode, retransmitMeshMessage.getNetworkPdu().get(segO));
                }
            }
        }
    }

    /**
     * Parses the raw encrypted mesh network pdu
     *
     * @param pdu mesh pdu to be parsed
     */
    protected boolean parseMeshPdu(final byte[] pdu) {
        return false;
    }

    /**
     * Parses control message and returns the underlying configuration message
     *
     * @param controlMessage control message to be passed
     * @param segmentCount   number of segments
     */
    protected final void parseControlMessage(final ControlMessage controlMessage, final int segmentCount) {
        final TransportControlMessage transportControlMessage = controlMessage.getTransportControlMessage();
        switch (transportControlMessage.getState()) {
            case LOWER_TRANSPORT_BLOCK_ACKNOWLEDGEMENT:
                Log.v(TAG, "Acknowledgement payload: " + MeshParserUtils.bytesToHex(controlMessage.getTransportControlPdu(), false));
                mRetransmitPayloads.clear();
                mRetransmitPayloads.addAll(BlockAcknowledgementMessage.getSegmentsToBeRetransmitted(controlMessage.getTransportControlPdu(), segmentCount));
                mMeshStatusCallbacks.onBlockAcknowledgementReceived(mNode);
                break;
            default:
                Log.v(TAG, "Unexpected control message received, ignoring message");
                mMeshStatusCallbacks.onUnknownPduReceived(mNode);
                break;
        }
    }

    public ProvisionedMeshNode getMeshNode() {
        return mNode;
    }

    public boolean isSegmented() {
        return mPayloads.size() > 1;
    }

    @Override
    public void onIncompleteTimerExpired() {
        Log.v(TAG, "Incomplete timer has expired, all segments were not received!");
        isIncompleteTimerExpired = true;
        if (meshMessageHandlerCallbacks != null) {

            final byte[] src = mSrc; //The destination of the message sent would be src address of the device
            meshMessageHandlerCallbacks.onIncompleteTimerExpired(mNode, src, true);

            if (mMeshStatusCallbacks != null) {
                final int srcAddress = AddressUtils.getUnicastAddressInt(src);
                mMeshStatusCallbacks.onTransactionFailed(mNode, srcAddress, true);
            }
        }
    }

    @Override
    public void sendSegmentAcknowledgementMessage(final ControlMessage controlMessage) {
        //We don't send acks here
        final ControlMessage message = mMeshTransport.createSegmentBlockAcknowledgementMessage(controlMessage);
        Log.v(TAG, "Sending acknowledgement: " + MeshParserUtils.bytesToHex(message.getNetworkPdu().get(0), false));
        mInternalTransportCallbacks.sendPdu(mNode, message.getNetworkPdu().get(0));
        mMeshStatusCallbacks.onBlockAcknowledgementSent(mNode);
    }

    public boolean isIncompleteTimerExpired() {
        return isIncompleteTimerExpired;
    }

    public enum MessageState {
        //Configuration message States
        COMPOSITION_DATA_GET_STATE(0),
        APP_KEY_ADD_STATE(1),
        CONFIG_MODEL_APP_BIND_STATE(2),
        CONFIG_MODEL_APP_UNBIND_STATE(3),
        CONFIG_MODEL_PUBLICATION_SET_STATE(4),
        CONFIG_MODEL_SUBSCRIPTION_ADD_STATE(5),
        CONFIG_MODEL_SUBSCRIPTION_DELETE_STATE(6),
        CONFIG_NODE_RESET_STATE(7),

        //Application message States
        GENERIC_ON_OFF_GET_STATE(200),
        GENERIC_ON_OFF_SET_STATE(201),
        GENERIC_ON_OFF_SET_UNACKNOWLEDGED_STATE(202),

        GENERIC_LEVEL_GET_STATE(203),
        GENERIC_LEVEL_SET_STATE(204),
        GENERIC_LEVEL_SET_UNACKNOWLEDGED_STATE(205),

        VENDOR_MODEL_ACKNOWLEDGED_STATE(1000),
        VENDOR_MODEL_UNACKNOWLEDGED_STATE(1001);

        private int state;

        MessageState(final int state) {
            this.state = state;
        }

        public int getState() {
            return state;
        }
    }
}
