package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.Iterator;
import java.util.List;

import org.netlib.util.booleanW;

import dataStructures.serializableGraph.SerializableSimpleGraph;
import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;

import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;

import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

/**
 * <pre>
 * This behaviour allows an agent to explore the environment and learn the associated topological map.
 * The algorithm is a pseudo - DFS computationally consuming because its not optimised at all.
 * 
 * When all the nodes around him are visited, the agent randomly select an open node and go there to restart its dfs. 
 * This (non optimal) behaviour is done until all nodes are explored. 
 * 
 * Warning, this behaviour does not save the content of visited nodes, only the topology.
 * Warning, the sub-behaviour ShareMap periodically share the whole map
 * </pre>
 * 
 * @author hc
 *
 */
public class ExploCoopBehaviour extends SimpleBehaviour {

	private static final long serialVersionUID = 8567689731496787661L;

	private boolean finished = false;

	/**
	 * Current knowledge of the agent regarding the environment
	 */
	private MapRepresentation myMap;

	private java.util.HashMap<String, SerializableSimpleGraph<String, MapAttribute>> pendingDeltas = new java.util.HashMap<>();
	private java.util.HashMap<String, Long> lastShareTopo = new java.util.HashMap<>();

	private String lastPosition = "";

	private List<String> list_agentNames;

	/**
	 * 
	 * @param myagent    reference to the agent we are adding this behaviour to
	 * @param myMap      known map of the world the agent is living in
	 * @param agentNames name of the agents to share the map with
	 */
	public ExploCoopBehaviour(final AbstractDedaleAgent myagent, MapRepresentation myMap, List<String> agentNames) {
		super(myagent);
		this.myMap = myMap;
		this.list_agentNames = agentNames;

	}

	private void sendMapDelta(String receiverName, jade.core.AID receiverAID) {
		long currentTime = System.currentTimeMillis();
		// Avoid sending the same map delta multiple times within a short window (1
		// second)
		// This prevents redundant sends if A and B ping each other simultaneously
		if (currentTime - lastShareTopo.getOrDefault(receiverName, 0L) < 1000) {
			return;
		}

		SerializableSimpleGraph<String, MapAttribute> sgToSend = this.myMap.getMapDelta(receiverName);
		if (sgToSend != null) {
			lastShareTopo.put(receiverName, currentTime);
			String convId = java.util.UUID.randomUUID().toString();
			pendingDeltas.put(convId, sgToSend);

			ACLMessage shareMsg = new ACLMessage(ACLMessage.INFORM);
			shareMsg.setProtocol("SHARE-TOPO");
			shareMsg.setSender(this.myAgent.getAID());
			shareMsg.addReceiver(receiverAID);
			shareMsg.setConversationId(convId);
			try {
				shareMsg.setContentObject(sgToSend);
				((AbstractDedaleAgent) this.myAgent).sendMessage(shareMsg);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void action() {

		if (this.myMap == null) {
			this.myMap = new MapRepresentation(this.myAgent.getLocalName());
		}

		// 0) Retrieve the current position
		Location myPosition = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
		String nextNodeId = null;

		if (myPosition != null) {
			// Broadcast MAP only if we moved to a new node and map is not fully explored
			if (!myPosition.getLocationId().equals(lastPosition)) {
				lastPosition = myPosition.getLocationId();

				if (this.myMap.hasOpenNode()) {
					for (String agentName : list_agentNames) {
						ACLMessage mapMsg = new ACLMessage(ACLMessage.INFORM);
						mapMsg.setProtocol("MAP");
						mapMsg.setSender(this.myAgent.getAID());
						mapMsg.addReceiver(new jade.core.AID(agentName, jade.core.AID.ISLOCALNAME));
						((AbstractDedaleAgent) this.myAgent).sendMessage(mapMsg);
					}
				}
			}

			// List of observable from the agent's current position
			List<Couple<Location, List<Couple<Observation, String>>>> lobs = ((AbstractDedaleAgent) this.myAgent)
					.observe();// myPosition

			/**
			 * Just added here to let you see what the agent is doing, otherwise he will be
			 * too quick
			 */
			try {
				this.myAgent.doWait(1000);
			} catch (Exception e) {
				e.printStackTrace();
			}

			// 1) remove the current node from openlist and add it to closedNodes.
			this.myMap.addNode(myPosition.getLocationId(), MapAttribute.closed);

			// 2) get the surrounding nodes and, if not in closedNodes, add them to open
			// nodes.
			Iterator<Couple<Location, List<Couple<Observation, String>>>> iter = lobs.iterator();
			while (iter.hasNext()) {
				Location accessibleNode = iter.next().getLeft();
				boolean isNewNode = this.myMap.addNewNode(accessibleNode.getLocationId());
				// the node may exist, but not necessarily the edge
				if (myPosition.getLocationId() != accessibleNode.getLocationId()) {
					this.myMap.addEdge(myPosition.getLocationId(), accessibleNode.getLocationId());
					if (nextNodeId == null && isNewNode)
						nextNodeId = accessibleNode.getLocationId();
				}
			}
			// 4) At each time step, the agent check if he received a graph from a teammate.
			// This is done unconditionally so agents that have no more open nodes can still
			// help teammates and share maps.
			MessageTemplate msgTemplate = MessageTemplate.and(
					MessageTemplate.MatchPerformative(ACLMessage.INFORM),
					MessageTemplate.or(
							MessageTemplate.MatchProtocol("SHARE-TOPO"),
							MessageTemplate.or(
									MessageTemplate.MatchProtocol("MAP"),
									MessageTemplate.or(
											MessageTemplate.MatchProtocol("SYNC-REQ"),
											MessageTemplate.MatchProtocol("ACK")))));
			ACLMessage msgReceived = this.myAgent.receive(msgTemplate);
			while (msgReceived != null) {
				String protocol = msgReceived.getProtocol();
				String senderName = msgReceived.getSender().getLocalName();

				if ("MAP".equals(protocol)) {
					// If they sent us a MAP, they might have something new for us, so we request it
					ACLMessage replyMsg = new ACLMessage(ACLMessage.INFORM);
					replyMsg.setProtocol("SYNC-REQ");
					replyMsg.setSender(this.myAgent.getAID());
					replyMsg.addReceiver(msgReceived.getSender());
					((AbstractDedaleAgent) this.myAgent).sendMessage(replyMsg);

					// In addition to requesting their map, if we have something to send,
					// proactively send ours!
					SerializableSimpleGraph<String, MapAttribute> sgToSend = this.myMap.getMapDelta(senderName);
					if (sgToSend != null) {
						sendMapDelta(senderName, msgReceived.getSender());
					}

				} else if ("SYNC-REQ".equals(protocol)) {
					SerializableSimpleGraph<String, MapAttribute> sgToSend = this.myMap.getMapDelta(senderName);
					if (sgToSend != null) {
						sendMapDelta(senderName, msgReceived.getSender());
					}

				} else if ("SHARE-TOPO".equals(protocol)) {
					try {
						SerializableSimpleGraph<String, MapAttribute> sgreceived = (SerializableSimpleGraph<String, MapAttribute>) msgReceived
								.getContentObject();
						this.myMap.mergeMap(sgreceived, senderName);

						ACLMessage ackMsg = new ACLMessage(ACLMessage.INFORM);
						ackMsg.setProtocol("ACK");
						ackMsg.setSender(this.myAgent.getAID());
						ackMsg.addReceiver(msgReceived.getSender());
						ackMsg.setInReplyTo(msgReceived.getConversationId());
						((AbstractDedaleAgent) this.myAgent).sendMessage(ackMsg);
					} catch (UnreadableException e) {
						e.printStackTrace();
					}

				} else if ("ACK".equals(protocol)) {
					String convId = msgReceived.getInReplyTo();
					if (convId != null) {
						SerializableSimpleGraph<String, MapAttribute> confirmedDelta = pendingDeltas.remove(convId);
						if (confirmedDelta != null) {
							this.myMap.markAsKnown(senderName, confirmedDelta);
						}
					}
				}
				msgReceived = this.myAgent.receive(msgTemplate);
			}

			// 5) select next move.
			if (finished || !this.myMap.hasOpenNode()) {
				finished = true;
				System.out
						.println(this.myAgent.getLocalName() + " - Exploration successufully done, behaviour removed.");
				for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
					String accessibleNodeId = obs.getLeft().getLocationId();
					if (!accessibleNodeId.equals(myPosition.getLocationId())
							&& !contientAgents(accessibleNodeId)) {
						nextNodeId = accessibleNodeId;
						break;
					}
				}
			} else {
				// 5.1 If there exist one open node directly reachable, go for it,
				// otherwise choose one from the openNode list, compute the shortestPath and go
				// for it
				if (nextNodeId == null) {
					// no directly accessible openNode
					// chose one, compute the path and take the first step.
					List<String> path = this.myMap.getShortestPathToClosestOpenNode(myPosition.getLocationId());
					if (path != null && !path.isEmpty()) {
						nextNodeId = path.get(0);
					}
				}

				if (nextNodeId != null && contientAgents(nextNodeId)) {
					for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
						String accessibleNodeId = obs.getLeft().getLocationId();
						if (!accessibleNodeId.equals(myPosition.getLocationId())
								&& !contientAgents(accessibleNodeId)) {
							nextNodeId = accessibleNodeId;
							break;
						}
					}
				}
			}

		}
		if (nextNodeId != null) {
			((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(nextNodeId));
		}

	}

	@Override
	public boolean done() {
		return finished;
	}

	private boolean contientAgents(String nodeID) {
		List<Couple<Location, List<Couple<Observation, String>>>> lobs = ((AbstractDedaleAgent) this.myAgent)
				.observe();// myPosition

		for (Couple<Location, List<Couple<Observation, String>>> points : lobs) {
			if (points.getLeft().getLocationId().equals(nodeID)) {
				for (Couple<Observation, String> obs : points.getRight()) {
					if (obs.getLeft() == Observation.AGENTNAME) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
