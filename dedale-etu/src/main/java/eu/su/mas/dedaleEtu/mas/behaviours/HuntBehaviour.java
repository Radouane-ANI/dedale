package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.List;

import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import jade.core.AID;
import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;

/**
 * HuntBehaviour: Tracks and intercepts Golems after map exploration is done.
 * Implements Étape 3 of the Golem Hunt workflow.
 */
public class HuntBehaviour extends SimpleBehaviour {

	private static final long serialVersionUID = 1L;
	private MapRepresentation myMap;
	private List<String> agentNames;

	public HuntBehaviour(AbstractDedaleAgent myagent, MapRepresentation myMap, List<String> agentNames) {
		super(myagent);
		this.myMap = myMap;
		this.agentNames = agentNames;
	}

	@Override
	public void action() {
		// Wait a bit to not go too fast
		try {
			this.myAgent.doWait(500);
		} catch (Exception e) {
			e.printStackTrace();
		}

		Location myPosition = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
		if (myPosition == null) return;

		// 1. Observe the environment
		List<Couple<Location, List<Couple<Observation, String>>>> lobs = ((AbstractDedaleAgent) this.myAgent).observe();
			
		// 2. Collect Odors & Gossip
		String bestAdjacentStenchNode = null;
		int highestStenchValue = -1;
		long currentTimestamp = System.currentTimeMillis();
			
		for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
			String locId = obs.getLeft().getLocationId();
			for (Couple<Observation, String> o : obs.getRight()) {
				if (o.getLeft() == Observation.STENCH) {
					int stenchValue = 0;
					try {
						stenchValue = Integer.parseInt(o.getRight());
					} catch (NumberFormatException e) {
						// Fallback if parsing fails
						stenchValue = 1;
					}
						
					// Add to local map memory
					this.myMap.addStench(locId, stenchValue, currentTimestamp);
						
					// Gossip to other agents
					broadcastGolemTrail(locId, stenchValue, currentTimestamp);
						
					// Traqueur: find the node with the STRONGEST stench (assuming higher value = stronger odor)
					if (!locId.equals(myPosition.getLocationId()) && stenchValue > highestStenchValue) {
						highestStenchValue = stenchValue;
						bestAdjacentStenchNode = locId;
					}
				}
			}
		}
			
		// 3. Clean old stenches
		this.myMap.cleanOldStenches(3000); // 15 seconds max age
			
		// 4. Selection of Target (Traqueur / Intercepteur)
		String nextNodeId = null;
			
		if (bestAdjacentStenchNode != null) {
			// LOGIQUE TRAQUEUR: We have an adjacent stench, go towards the strongest one.
			nextNodeId = bestAdjacentStenchNode;
		} else {
			// LOGIQUE INTERCEPTEUR: No adjacent stench. Look for the closest one in the map (Gossip / Memory).
			List<String> shortestPathToStench = null;
			int minDistance = Integer.MAX_VALUE;
				
			for (String stenchNode : this.myMap.getStenchNodes()) {
				if (stenchNode.equals(myPosition.getLocationId())) continue;
					
				List<String> path = this.myMap.getShortestPath(myPosition.getLocationId(), stenchNode);
				if (path != null && path.size() < minDistance) {
					minDistance = path.size();
					shortestPathToStench = path;
				}
			}
				
			if (shortestPathToStench != null && !shortestPathToStench.isEmpty()) {
				// Interceptor movement: Take the first step towards the closest stench node
				nextNodeId = shortestPathToStench.get(0);
			} else {
				// No stench known at all -> fallback to patrol (pick random non-occupied node)
				for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
					String accessibleNodeId = obs.getLeft().getLocationId();
					if (!accessibleNodeId.equals(myPosition.getLocationId()) && !containsAgent(obs.getRight())) {
						nextNodeId = accessibleNodeId;
						break;
					}
				}
				// If all adjacent nodes are occupied, just pick any valid adjacent
				if (nextNodeId == null && lobs.size() > 1) {
					for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
						if (!obs.getLeft().getLocationId().equals(myPosition.getLocationId())) {
							nextNodeId = obs.getLeft().getLocationId();
							break;
						}
					}
				}
			}
		}
			
		// 5. Move
		if (nextNodeId != null) {
			((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(nextNodeId));
		}
	}

	@Override
	public boolean done() {
		return false; // HuntBehaviour runs forever
	}

	private void broadcastGolemTrail(String nodeId, int stenchValue, long timestamp) {
		if (this.agentNames == null || this.agentNames.isEmpty()) return;
		
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.setProtocol("GOLEM_TRAIL");
		msg.setSender(this.myAgent.getAID());
		for (String agentName : this.agentNames) {
			msg.addReceiver(new AID(agentName, AID.ISLOCALNAME));
		}
		msg.setContent(nodeId + "," + stenchValue + "," + timestamp);
		((AbstractDedaleAgent) this.myAgent).sendMessage(msg);
	}
	
	private boolean containsAgent(List<Couple<Observation, String>> obsList) {
		for (Couple<Observation, String> obs : obsList) {
			if (obs.getLeft() == Observation.AGENTNAME) {
				return true;
			}
		}
		return false;
	}
}
