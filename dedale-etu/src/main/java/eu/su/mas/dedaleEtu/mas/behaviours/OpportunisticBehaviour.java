package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;

/**
 * OpportunisticBehaviour: A unified behaviour that dynamically switches between
 * EXPLORE and HUNT states based on Stench freshness without destroying JADE behaviours.
 */
public class OpportunisticBehaviour extends TickerBehaviour {

	private static final long serialVersionUID = 1L;

	public enum State { EXPLORE, HUNT }
	private State currentState = State.EXPLORE;

	private MapRepresentation myMap;
	private ShareMapFSMBehaviour shareBehaviour;
	private ReceiveGolemTrailBehaviour receiveTrailBehaviour;
	private ReceiveSiegeStatusBehaviour receiveSiegeBehaviour;
	private List<String> agentNames;

	private boolean finishedExploration = false;
	private int consecutiveWaitTicks = 0;

	public OpportunisticBehaviour(final AbstractDedaleAgent myagent, MapRepresentation myMap,
			ShareMapFSMBehaviour shareBehaviour, ReceiveGolemTrailBehaviour receiveTrailBehaviour,
			ReceiveSiegeStatusBehaviour receiveSiegeBehaviour, List<String> agentNames) {
		super(myagent, 500); // 500ms ticks execution
		this.myMap = myMap;
		this.shareBehaviour = shareBehaviour;
		this.receiveTrailBehaviour = receiveTrailBehaviour;
		this.receiveSiegeBehaviour = receiveSiegeBehaviour;
		this.agentNames = agentNames;
	}

	@Override
	public void onTick() {

		if (this.myMap == null) {
			this.myMap = new MapRepresentation(this.myAgent.getLocalName());
			if (this.shareBehaviour != null) this.shareBehaviour.setMap(this.myMap);
			if (this.receiveTrailBehaviour != null) this.receiveTrailBehaviour.setMap(this.myMap);
			if (this.receiveSiegeBehaviour != null) this.receiveSiegeBehaviour.setMap(this.myMap);
		}

		Location myPosition = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
		if (myPosition == null) return;

		// --- 1. OBSERVATION & MAPPING (Unified) ---
		List<Couple<Location, List<Couple<Observation, String>>>> lobs = ((AbstractDedaleAgent) this.myAgent).observe();

		this.myMap.addNode(myPosition.getLocationId(), MapAttribute.closed);

		long currentTimestamp = System.currentTimeMillis();
		boolean newlyExploredNodes = false;

		List<String> obstacles = new ArrayList<>();
		String visibleGolemNode = null;
		boolean allyVisible = false;

		for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
			Location accessibleNode = obs.getLeft();
			String locId = accessibleNode.getLocationId();

			// Ensure open nodes and edges are mapped, even when hunting
			boolean isNewNode = this.myMap.addNewNode(locId);
			if (isNewNode) newlyExploredNodes = true;
			
			if (!myPosition.getLocationId().equals(locId)) {
				this.myMap.addEdge(myPosition.getLocationId(), locId);
			}

			// Process observations (Stench, Agent)
			if (containsAlly(obs.getRight())) {
				obstacles.add(locId);
				allyVisible = true;
			}
			String enemy = getEnemyName(obs.getRight());
			if (enemy != null) {
				visibleGolemNode = locId;
			}

			for (Couple<Observation, String> o : obs.getRight()) {
				if (o.getLeft() == Observation.STENCH) {
					int stenchValue = 1;
					try {
						stenchValue = Integer.parseInt(o.getRight());
					} catch (NumberFormatException e) { }
					this.myMap.addStench(locId, stenchValue, currentTimestamp);
					broadcastGolemTrail(locId, stenchValue, currentTimestamp);
				}
			}
		}

		// Add remote siege staff to obstacles
		List<String> siegeStaff = this.myMap.getSiegeStaffLocations();
		for(String s : siegeStaff) {
			if(!obstacles.contains(s) && !s.equals(myPosition.getLocationId())) {
				obstacles.add(s);
			}
		}

		// Clean old stenches and siege status
		this.myMap.cleanOldStenches(5000); // 5 sec decay
		this.myMap.cleanOldSiegeData(3000); // 3 sec decay

		// Check if exploration is officially done globally
		if (!finishedExploration && !this.myMap.hasOpenNode()) {
			finishedExploration = true;
			System.out.println(this.myAgent.getLocalName() + " - Topology is completely mapped !");
		}


		// --- 2. THE BRAIN: DYNAMIC STATE TOGGLE ---
		// We switch to HUNT if we see the Golem OR if we have very fresh stenches globally.
		Set<String> allStenches = this.myMap.getStenchNodes();
		long maxTimestamp = -1;
		for (String n : allStenches) {
			long ts = this.myMap.getStenchTimestamp(n);
			if (ts > maxTimestamp) maxTimestamp = ts;
		}

		boolean isTrailHot = (maxTimestamp != -1 && (currentTimestamp - maxTimestamp) < 4000); // Trail freshness threshold 4s
		
		State previousState = this.currentState;
		if (visibleGolemNode != null || isTrailHot) {
			this.currentState = State.HUNT;
		} else {
			this.currentState = State.EXPLORE;
		}

		if (previousState != this.currentState) {
			System.out.println(this.myAgent.getLocalName() + " -> Switching state to " + this.currentState.toString());
		}

		// --- 3. MOVEMENT DECISION ---
		String targetNodeId = null;

		if (this.currentState == State.HUNT) {
			targetNodeId = computeHuntTarget(myPosition, lobs, obstacles, visibleGolemNode, allStenches, currentTimestamp, maxTimestamp, allyVisible);
		} else {
			targetNodeId = computeExploreTarget(myPosition, lobs);
		}

		// --- 4. EXECUTE MOVEMENT ---
		if (targetNodeId != null) {
			if (!targetNodeId.equals(myPosition.getLocationId())) {
				((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(targetNodeId));
			}
		}
	}


	// -----------------------------------------------------------
	// ----------------- EXPLORE LOGIC ---------------------------
	// -----------------------------------------------------------
	private String computeExploreTarget(Location myPosition, List<Couple<Location, List<Couple<Observation, String>>>> lobs) {
		String nextNodeId = null;

		if (finishedExploration) {
			// Random walk fallback if map is fully explored and we have no stench
			List<String> validPatrolNodes = new ArrayList<>();
			for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
				String accessibleNodeId = obs.getLeft().getLocationId();
				if (!accessibleNodeId.equals(myPosition.getLocationId()) && !containsAlly(obs.getRight())) {
					validPatrolNodes.add(accessibleNodeId);
				}
			}
			if (!validPatrolNodes.isEmpty()) {
				java.util.Collections.shuffle(validPatrolNodes);
				return validPatrolNodes.get(0);
			}
			return null;
		}

		// 1. Try to find a directly accessible open node
		for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
			String accessibleNodeId = obs.getLeft().getLocationId();
			if (!accessibleNodeId.equals(myPosition.getLocationId()) && !containsAlly(obs.getRight())) {
				// If it's an open node, prefer it directly
				// Well, to know if it's open, we can just grab ShortestPath... 
				// Actually ExploCoop just grabbed any unvisited, but here we can check the map:
				// If we don't check, the exact Explo logic was: compute path to closest.
			}
		}

		// Use shortest path
		List<String> path = this.myMap.getShortestPathToClosestOpenNode(myPosition.getLocationId());
		if (path != null && !path.isEmpty()) {
			nextNodeId = path.get(0);
		}

		// Anti collision for Explo
		if (nextNodeId != null && contientAgentsStrict(nextNodeId, lobs)) {
			// Pick alternative
			for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
				String alternativeNodeId = obs.getLeft().getLocationId();
				if (!alternativeNodeId.equals(myPosition.getLocationId()) && !contientAgentsStrict(alternativeNodeId, lobs)) {
					nextNodeId = alternativeNodeId;
					break;
				}
			}
		}

		return nextNodeId;
	}


	// -----------------------------------------------------------
	// ------------------- HUNT LOGIC ----------------------------
	// -----------------------------------------------------------
	private String computeHuntTarget(Location myPosition, List<Couple<Location, List<Couple<Observation, String>>>> lobs, 
			List<String> obstacles, String visibleGolemNode, Set<String> allStenches, long currentTimestamp, long maxTimestamp, boolean allyVisible) {
		
		String targetNodeId = null;
		String actualGolemPos = null;
		List<String> bestPath = null;
		
		if (visibleGolemNode != null) {
			actualGolemPos = visibleGolemNode;
		} else {
			Set<String> freshestStenches = new HashSet<>();
			if (maxTimestamp != -1) {
				for (String n : allStenches) {
					if (this.myMap.getStenchTimestamp(n) >= maxTimestamp - 100) {
						freshestStenches.add(n);
					}
				}
			}
			Set<String> possibleLocs = this.myMap.getGolemPossibleLocations(freshestStenches);
			if (possibleLocs != null && possibleLocs.size() == 1) {
				actualGolemPos = possibleLocs.iterator().next();
			} else if (this.myMap.getSiegeGolemPos() != null) {
				actualGolemPos = this.myMap.getSiegeGolemPos();
			}
		}

		if (actualGolemPos != null) {
			List<String> neighbors = this.myMap.getNeighbors(actualGolemPos);
			int A = (this.agentNames != null ? this.agentNames.size() : 0) + 1;
			int D = neighbors.size();
			
			// Si on est opportuniste dans l'inconnu, D est potentiellement plus elevé.
			// Toutefois, on travaille avec les infos connues.
			
			// Si on est en position
			boolean inPosition = neighbors.contains(myPosition.getLocationId()) || myPosition.getLocationId().equals(actualGolemPos);
			
			Set<String> holes = new HashSet<>(neighbors);
			holes.removeAll(obstacles);
			holes.remove(myPosition.getLocationId());

			if (inPosition && allyVisible) {
				broadcastSiegeStatus(actualGolemPos, String.join(",", holes));
			}

			if (inPosition && !myPosition.getLocationId().equals(actualGolemPos)) {
				return myPosition.getLocationId(); // Restons ici pour bloquer
			} else {
				String bestHole = null;
				int minDist = Integer.MAX_VALUE;
				
				List<String> sortedHoles = new ArrayList<>(holes);
				if (A < D) {
					sortedHoles.sort((h1, h2) -> Integer.compare(this.myMap.getNeighbors(h2).size(), this.myMap.getNeighbors(h1).size()));
				}
				
				List<String> obstaclesPourContournement = new ArrayList<>(obstacles);
				obstaclesPourContournement.add(actualGolemPos);

				for (String hole : sortedHoles) {
					List<String> path = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), hole, obstaclesPourContournement);
					if (path != null && path.size() < minDist) {
						minDist = path.size();
						bestHole = hole;
						bestPath = path;
					}
				}
				
				if (bestHole != null) targetNodeId = bestHole;
				else targetNodeId = actualGolemPos;
			}

		} else {
			// Fallback Stench Chase
			int minDist = Integer.MAX_VALUE;
			long bestTs = -1;

			for (String loc : allStenches) {
				if (loc.equals(myPosition.getLocationId())) continue;
				if (obstacles.contains(loc)) continue;

				long ts = this.myMap.getStenchTimestamp(loc);
				List<String> path = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), loc, obstacles);

				if (path != null && !path.isEmpty()) {
					if (ts > bestTs || (ts == bestTs && path.size() < minDist)) {
						bestTs = ts;
						minDist = path.size();
						targetNodeId = loc;
						bestPath = path;
					}
				}
			}
		}

		String nextNodeId = null;
		if (targetNodeId != null) {
			if (targetNodeId.equals(myPosition.getLocationId())) {
				nextNodeId = myPosition.getLocationId();
			} else {
				List<String> path = (bestPath != null) ? bestPath
						: this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), targetNodeId, obstacles);
				if (path != null && !path.isEmpty()) {
					if (visibleGolemNode != null && path.get(0).equals(visibleGolemNode)) {
						nextNodeId = myPosition.getLocationId();
					} else {
						nextNodeId = path.get(0);
					}
				}
			}
		}

		// Fallback random walk for hunt if totally stuck
		if (nextNodeId == null) {
			List<String> validPatrolNodes = new ArrayList<>();
			for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
				String accessibleNodeId = obs.getLeft().getLocationId();
				if (!accessibleNodeId.equals(myPosition.getLocationId()) && !containsAlly(obs.getRight())) {
					validPatrolNodes.add(accessibleNodeId);
				}
			}
			if (!validPatrolNodes.isEmpty()) {
				java.util.Collections.shuffle(validPatrolNodes);
				nextNodeId = validPatrolNodes.get(0);
			}
		}

		return nextNodeId;
	}


	// -----------------------------------------------------------
	// -------------------- UTILITIES ----------------------------
	// -----------------------------------------------------------

	private void broadcastGolemTrail(String nodeId, int stenchValue, long timestamp) {
		if (this.agentNames == null || this.agentNames.isEmpty()) return;
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.setProtocol("GOLEM_TRAIL");
		msg.setSender(this.myAgent.getAID());
		for (String agentName : this.agentNames) msg.addReceiver(new AID(agentName, AID.ISLOCALNAME));
		msg.setContent(nodeId + "," + stenchValue + "," + timestamp);
		((AbstractDedaleAgent) this.myAgent).sendMessage(msg);
	}

	private void broadcastSiegeStatus(String golemPos, String holesStr) {
		if (this.agentNames == null || this.agentNames.isEmpty()) return;
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.setProtocol("SIEGE_STATUS");
		msg.setSender(this.myAgent.getAID());
		for (String agentName : this.agentNames) msg.addReceiver(new AID(agentName, AID.ISLOCALNAME));
		Location myPos = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
		String myLocStr = myPos != null ? myPos.getLocationId() : "null";
		String content = golemPos + ";" + this.myAgent.getLocalName() + ":" + myLocStr + ";" + holesStr + ";" + System.currentTimeMillis();
		msg.setContent(content);
		((AbstractDedaleAgent) this.myAgent).sendMessage(msg);
	}

	private boolean containsAlly(List<Couple<Observation, String>> obsList) {
		for (Couple<Observation, String> obs : obsList) {
			if (obs.getLeft() == Observation.AGENTNAME) {
				String name = obs.getRight();
				if (this.agentNames != null && this.agentNames.contains(name)) return true;
			}
		}
		return false;
	}

	private boolean contientAgentsStrict(String nodeID, List<Couple<Location, List<Couple<Observation, String>>>> lobs) {
		for (Couple<Location, List<Couple<Observation, String>>> points : lobs) {
			if (points.getLeft().getLocationId().equals(nodeID)) {
				return containsAlly(points.getRight());
			}
		}
		return false;
	}

	private String getEnemyName(List<Couple<Observation, String>> obsList) {
		for (Couple<Observation, String> obs : obsList) {
			if (obs.getLeft() == Observation.AGENTNAME) {
				String name = obs.getRight();
				if (this.agentNames != null && !this.agentNames.contains(name)
						&& !name.equals(this.myAgent.getLocalName())) {
					return name;
				}
			}
		}
		return null;
	}

}
