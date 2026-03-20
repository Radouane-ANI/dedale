package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.HashMap;
import java.util.List;

import dataStructures.serializableGraph.SerializableSimpleGraph;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;

import jade.core.AID;
import jade.core.behaviours.FSMBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

/**
 * FSMBehaviour implementing the map sharing protocol between agents.
 * 
 * <pre>
 * States:
 *   CheckMessages  – check incoming messages, decide next action
 *   SendMapPing    – broadcast MAP ping to all agents
 *   HandleMapPing  – respond to a MAP ping with SYNC-REQ + proactive delta
 *   HandleSyncReq  – respond to a SYNC-REQ by sending SHARE-TOPO delta
 *   HandleShareTopo – merge received topology and send ACK
 *   HandleAck      – confirm a pending delta as known
 *
 * The FSM loops indefinitely (no last state).
 * </pre>
 */
public class ShareMapFSMBehaviour extends FSMBehaviour {

    private static final long serialVersionUID = 1L;

    // State names
    private static final String STATE_CHECK = "CheckMessages";
    private static final String STATE_SEND_PING = "SendMapPing";
    private static final String STATE_HANDLE_MAP = "HandleMapPing";
    private static final String STATE_HANDLE_SYNC = "HandleSyncReq";
    private static final String STATE_HANDLE_TOPO = "HandleShareTopo";
    private static final String STATE_HANDLE_ACK = "HandleAck";

    // Transition event codes
    private static final int EVT_NO_MSG = 0;
    private static final int EVT_MAP_RECEIVED = 1;
    private static final int EVT_SYNC_REQ_RECEIVED = 2;
    private static final int EVT_SHARE_TOPO_RECEIVED = 3;
    private static final int EVT_ACK_RECEIVED = 4;

    // Shared data
    private MapRepresentation myMap;
    private List<String> agentNames;
    private HashMap<String, SerializableSimpleGraph<String, MapAttribute>> pendingDeltas = new HashMap<>();
    private HashMap<String, Long> lastShareTopo = new HashMap<>();

    /**
     * Temporarily holds the message selected by CheckMessages for the next state
     */
    private ACLMessage currentMsg;

    /** Template matching all sharing‐related protocols */
    private final MessageTemplate shareTemplate = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.or(
                    MessageTemplate.MatchProtocol("SHARE-TOPO"),
                    MessageTemplate.or(
                            MessageTemplate.MatchProtocol("MAP"),
                            MessageTemplate.or(
                                    MessageTemplate.MatchProtocol("SYNC-REQ"),
                                    MessageTemplate.MatchProtocol("ACK")))));

    // Constructor

    public ShareMapFSMBehaviour(AbstractDedaleAgent agent, MapRepresentation map, List<String> agentNames) {
        super(agent);
        this.myMap = map;
        this.agentNames = agentNames;

        // Register states
        registerFirstState(new CheckMessagesBehaviour(), STATE_CHECK);
        registerState(new SendMapPingBehaviour(), STATE_SEND_PING);
        registerState(new HandleMapPingBehaviour(), STATE_HANDLE_MAP);
        registerState(new HandleSyncReqBehaviour(), STATE_HANDLE_SYNC);
        registerState(new HandleShareTopoBehaviour(), STATE_HANDLE_TOPO);
        registerState(new HandleAckBehaviour(), STATE_HANDLE_ACK);

        // Register transitions from CheckMessages
        registerTransition(STATE_CHECK, STATE_SEND_PING, EVT_NO_MSG);
        registerTransition(STATE_CHECK, STATE_HANDLE_MAP, EVT_MAP_RECEIVED);
        registerTransition(STATE_CHECK, STATE_HANDLE_SYNC, EVT_SYNC_REQ_RECEIVED);
        registerTransition(STATE_CHECK, STATE_HANDLE_TOPO, EVT_SHARE_TOPO_RECEIVED);
        registerTransition(STATE_CHECK, STATE_HANDLE_ACK, EVT_ACK_RECEIVED);

        // All handler states loop back to CheckMessages
        String[] resetStates = new String[] { STATE_CHECK };
        registerDefaultTransition(STATE_SEND_PING, STATE_CHECK, resetStates);
        registerDefaultTransition(STATE_HANDLE_MAP, STATE_CHECK, resetStates);
        registerDefaultTransition(STATE_HANDLE_SYNC, STATE_CHECK, resetStates);
        registerDefaultTransition(STATE_HANDLE_TOPO, STATE_CHECK, resetStates);
        registerDefaultTransition(STATE_HANDLE_ACK, STATE_CHECK, resetStates);
    }

    // Setter for lazy map init
    public void setMap(MapRepresentation map) {
        this.myMap = map;
    }

    // Helper: send a SHARE-TOPO delta to a specific agent
    private void sendMapDelta(String receiverName, AID receiverAID) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastShareTopo.getOrDefault(receiverName, 0L) < 1000) {
            return;
        }
        if (myMap == null)
            return;

        SerializableSimpleGraph<String, MapAttribute> sgToSend = this.myMap.getMapDelta(receiverName);
        if (sgToSend != null) {
            lastShareTopo.put(receiverName, currentTime);
            String convId = java.util.UUID.randomUUID().toString();
            pendingDeltas.put(convId, sgToSend);

            ACLMessage shareMsg = new ACLMessage(ACLMessage.INFORM);
            shareMsg.setProtocol("SHARE-TOPO");
            shareMsg.setSender(myAgent.getAID());
            shareMsg.addReceiver(receiverAID);
            shareMsg.setConversationId(convId);
            try {
                shareMsg.setContentObject(sgToSend);
                ((AbstractDedaleAgent) myAgent).sendMessage(shareMsg);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // =====================================================================
    // Inner behaviour classes (FSM states)
    // =====================================================================

    /**
     * STATE: CheckMessages
     * Reads one message from the queue and sets the transition event accordingly.
     * If no message is found, triggers a MAP ping broadcast.
     */
    private class CheckMessagesBehaviour extends OneShotBehaviour {
        private static final long serialVersionUID = 1L;
        private int exitCode = EVT_NO_MSG;

        @Override
        public void action() {
            currentMsg = myAgent.receive(shareTemplate);
            if (currentMsg == null) {
                exitCode = EVT_NO_MSG;
                // Small block to avoid busy-wait
                block(500);
            } else {
                String protocol = currentMsg.getProtocol();
                switch (protocol) {
                    case "MAP":
                        exitCode = EVT_MAP_RECEIVED;
                        break;
                    case "SYNC-REQ":
                        exitCode = EVT_SYNC_REQ_RECEIVED;
                        break;
                    case "SHARE-TOPO":
                        exitCode = EVT_SHARE_TOPO_RECEIVED;
                        break;
                    case "ACK":
                        exitCode = EVT_ACK_RECEIVED;
                        break;
                    default:
                        exitCode = EVT_NO_MSG;
                        break;
                }
            }
        }

        @Override
        public int onEnd() {
            return exitCode;
        }
    }

    /**
     * STATE: SendMapPing
     * Broadcasts a MAP message to all known agents when there are still open nodes.
     */
    private class SendMapPingBehaviour extends OneShotBehaviour {
        private static final long serialVersionUID = 1L;
        private int lastPingUpdateCount = -1;

        @Override
        public void action() {

            if (myMap == null || !myMap.hasOpenNode() || myMap.getUpdateCount() <= lastPingUpdateCount) {
                return;
            }
            lastPingUpdateCount = myMap.getUpdateCount();

            for (String agentName : agentNames) {
                ACLMessage mapMsg = new ACLMessage(ACLMessage.INFORM);
                mapMsg.setProtocol("MAP");
                mapMsg.setSender(myAgent.getAID());
                mapMsg.addReceiver(new AID(agentName, AID.ISLOCALNAME));
                ((AbstractDedaleAgent) myAgent).sendMessage(mapMsg);
            }
        }
    }

    /**
     * STATE: HandleMapPing
     * Received a MAP ping → reply with SYNC-REQ and proactively send our own delta.
     */
    private class HandleMapPingBehaviour extends OneShotBehaviour {
        private static final long serialVersionUID = 1L;

        @Override
        public void action() {
            if (currentMsg == null)
                return;
            String senderName = currentMsg.getSender().getLocalName();

            // Reply with SYNC-REQ
            ACLMessage replyMsg = new ACLMessage(ACLMessage.INFORM);
            replyMsg.setProtocol("SYNC-REQ");
            replyMsg.setSender(myAgent.getAID());
            replyMsg.addReceiver(currentMsg.getSender());
            ((AbstractDedaleAgent) myAgent).sendMessage(replyMsg);

            // Proactively send our delta if we have something new
            if (myMap != null) {
                SerializableSimpleGraph<String, MapAttribute> sgToSend = myMap.getMapDelta(senderName);
                if (sgToSend != null) {
                    sendMapDelta(senderName, currentMsg.getSender());
                }
            }
        }
    }

    /**
     * STATE: HandleSyncReq
     * Received a SYNC-REQ → send our delta via SHARE-TOPO.
     */
    private class HandleSyncReqBehaviour extends OneShotBehaviour {
        private static final long serialVersionUID = 1L;

        @Override
        public void action() {
            if (currentMsg == null)
                return;
            String senderName = currentMsg.getSender().getLocalName();

            if (myMap != null) {
                SerializableSimpleGraph<String, MapAttribute> sgToSend = myMap.getMapDelta(senderName);
                if (sgToSend != null) {
                    sendMapDelta(senderName, currentMsg.getSender());
                }
            }
        }
    }

    /**
     * STATE: HandleShareTopo
     * Received a SHARE-TOPO → merge the graph and send ACK.
     */
    private class HandleShareTopoBehaviour extends OneShotBehaviour {
        private static final long serialVersionUID = 1L;

        @Override
        public void action() {
            if (currentMsg == null)
                return;
            String senderName = currentMsg.getSender().getLocalName();

            try {
                @SuppressWarnings("unchecked")
                SerializableSimpleGraph<String, MapAttribute> sgreceived = (SerializableSimpleGraph<String, MapAttribute>) currentMsg
                        .getContentObject();
                if (myMap != null) {
                    myMap.mergeMap(sgreceived, senderName);
                }

                // Send ACK
                ACLMessage ackMsg = new ACLMessage(ACLMessage.INFORM);
                ackMsg.setProtocol("ACK");
                ackMsg.setSender(myAgent.getAID());
                ackMsg.addReceiver(currentMsg.getSender());
                ackMsg.setInReplyTo(currentMsg.getConversationId());
                ((AbstractDedaleAgent) myAgent).sendMessage(ackMsg);
            } catch (UnreadableException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * STATE: HandleAck
     * Received an ACK → confirm the pending delta as known by the receiver.
     */
    private class HandleAckBehaviour extends OneShotBehaviour {
        private static final long serialVersionUID = 1L;

        @Override
        public void action() {
            if (currentMsg == null)
                return;
            String senderName = currentMsg.getSender().getLocalName();
            String convId = currentMsg.getInReplyTo();

            if (convId != null) {
                SerializableSimpleGraph<String, MapAttribute> confirmedDelta = pendingDeltas.remove(convId);
                if (confirmedDelta != null && myMap != null) {
                    myMap.markAsKnown(senderName, confirmedDelta);
                }
            }
        }
    }
}
