package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;

/**
 * HuntBehaviour: Tracks and intercepts Golems after map exploration is done.
 * Implements Étape 3 of the Golem Hunt workflow.
 */
public class HuntBehaviour extends TickerBehaviour {

	private static final long serialVersionUID = 1L;
	private MapRepresentation myMap;
	private List<String> agentNames;

	public HuntBehaviour(AbstractDedaleAgent myagent, MapRepresentation myMap, List<String> agentNames) {
		super(myagent, 500);
		this.myMap = myMap;
		this.agentNames = agentNames;
	}

	@Override
	public void onTick() {

		Location myPosition = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
		if (myPosition == null)
			return;

		// 1. Observe the environment
		List<Couple<Location, List<Couple<Observation, String>>>> lobs = ((AbstractDedaleAgent) this.myAgent).observe();

		// 2. Collect Odors & Gossip
		long currentTimestamp = System.currentTimeMillis();
		boolean adjacentStenchPerceived = false;

		for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
			String locId = obs.getLeft().getLocationId();
			for (Couple<Observation, String> o : obs.getRight()) {
				if (o.getLeft() == Observation.STENCH) {
					int stenchValue = 0;
					try {
						stenchValue = Integer.parseInt(o.getRight());
					} catch (NumberFormatException e) {
						stenchValue = 1;
					}

					this.myMap.addStench(locId, stenchValue, currentTimestamp);
					broadcastGolemTrail(locId, stenchValue, currentTimestamp);

					if (!locId.equals(myPosition.getLocationId())) {
						adjacentStenchPerceived = true;
					}
				}
			}
		}

		// 3. Clean old stenches
		this.myMap.cleanOldStenches(3000); // 3 seconds max age for reactive hunt

		// 3.5. Identify Obstacles (Adjacent Allies)
		List<String> obstacles = new ArrayList<>();
		for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
			if (containsAgent(obs.getRight())) {
				obstacles.add(obs.getLeft().getLocationId());
			}
		}

		// 4. Selection of Target (Traqueur / Intercepteur) via Triangulation
		String nextNodeId = null;

		Set<String> allStenches = this.myMap.getStenchNodes();
		long maxTimestamp = -1;
		for (String n : allStenches) {
			long ts = this.myMap.getStenchTimestamp(n);
			if (ts > maxTimestamp) {
				maxTimestamp = ts;
			}
		}

		Set<String> freshestStenches = new HashSet<>();
		if (maxTimestamp != -1) {
			for (String n : allStenches) {
				// Group stenches created within a 1s window (allow for gossip latency)
				if (this.myMap.getStenchTimestamp(n) >= maxTimestamp - 1000) {
					freshestStenches.add(n);
				}
			}
		}

		Set<String> possibleLocs = this.myMap.getGolemPossibleLocations(freshestStenches);
		String targetNodeId = null;

		if (possibleLocs != null && !possibleLocs.isEmpty()) {
			// Find the closest possible location from deduced set
			int minDist = Integer.MAX_VALUE;
			for (String loc : possibleLocs) {
				if (loc.equals(myPosition.getLocationId()))
					continue;
				List<String> path = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), loc, obstacles);
				if (path != null && path.size() < minDist) {
					minDist = path.size();
					targetNodeId = loc;
				}
			}

			// If we are literally on the location, maybe the Golem is here or we collided.
			if (targetNodeId == null && possibleLocs.contains(myPosition.getLocationId())) {
				targetNodeId = myPosition.getLocationId(); // we are already on the best node or no path
			}
		} else {
			// Fallback: just go to the closest stench node if triangulation yields nothing
			int minDist = Integer.MAX_VALUE;
			for (String loc : allStenches) {
				if (loc.equals(myPosition.getLocationId()))
					continue;
				List<String> path = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), loc, obstacles);
				if (path != null && path.size() < minDist) {
					minDist = path.size();
					targetNodeId = loc;
				}
			}
		}

		// Roles: Interceptor or Tracker?
		// We'll use adjacentStenchPerceived as a simple heuristic:
		// If we perceive the stench directly, we TRACE it (Tracker).
		// Else if we don't perceive it but know about it via Gossip, we INTERCEPT (aim
		// for a node near target).
		if (targetNodeId != null && !targetNodeId.equals(myPosition.getLocationId())) {
			if (adjacentStenchPerceived) {
				// TRAQUEUR: Move directly towards the target
				List<String> path = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), targetNodeId,
						obstacles);
				if (path != null && !path.isEmpty()) {
					nextNodeId = path.get(0);
				}
			} else {
				// INTERCEPTEUR: Move towards target, but idealy stop 1 node away to cut off.
				// In Dedale, we can simply try to path to targetNodeId as well for now, or pick
				// an adjacent node of target.
				// For simplicity, interceptor just behaves as a tracker that paths to target.
				// Real interception (blocking) will be fully developed in Etape 5 (Degree).
				List<String> path = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), targetNodeId,
						obstacles);
				if (path != null && !path.isEmpty()) {
					nextNodeId = path.get(0);
				}
			}
		}

		// 5. Fallback Default Patrol
		if (nextNodeId == null) {
			for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
				String accessibleNodeId = obs.getLeft().getLocationId();
				if (!accessibleNodeId.equals(myPosition.getLocationId()) && !containsAgent(obs.getRight())) {
					nextNodeId = accessibleNodeId;
					break;
				}
			}
			if (nextNodeId == null && lobs.size() > 1) {
				for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
					if (!obs.getLeft().getLocationId().equals(myPosition.getLocationId())) {
						nextNodeId = obs.getLeft().getLocationId();
						break;
					}
				}
			}
		}

		// 6. Move
		if (nextNodeId != null) {
			((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(nextNodeId));
		}
	}

	private void broadcastGolemTrail(String nodeId, int stenchValue, long timestamp) {
		if (this.agentNames == null || this.agentNames.isEmpty())
			return;

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
			if (obs.getLeft() == Observation.AGENTNAME && this.agentNames.contains(obs.getRight())) {
				return true;
			}
		}
		return false;
	}
}
