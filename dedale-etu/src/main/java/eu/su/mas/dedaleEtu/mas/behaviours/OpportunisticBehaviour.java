package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Collections;

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
 * EXPLORE and HUNT states based on Stench freshness without destroying JADE
 * behaviours.
 */
public class OpportunisticBehaviour extends TickerBehaviour {

	private static final long serialVersionUID = 1L;

	public enum State {
		EXPLORE, HUNT
	}

	private State currentState = State.EXPLORE;

	private MapRepresentation myMap;
	private ShareMapFSMBehaviour shareBehaviour;
	private ReceiveGolemTrailBehaviour receiveTrailBehaviour;
	private ReceiveSiegeStatusBehaviour receiveSiegeBehaviour;
	private List<String> agentNames;

	private boolean finishedExploration = false;
	private int consecutiveWaitTicks = 0;
	private String lastPosition = null; // [FIX-4] Pour detection de deadlock

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
			if (this.shareBehaviour != null)
				this.shareBehaviour.setMap(this.myMap);
			if (this.receiveTrailBehaviour != null)
				this.receiveTrailBehaviour.setMap(this.myMap);
			if (this.receiveSiegeBehaviour != null)
				this.receiveSiegeBehaviour.setMap(this.myMap);
		}

		Location myPosition = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
		if (myPosition == null)
			return;

		long currentTimestamp = System.currentTimeMillis();
		java.util.Map<String, Couple<Integer, String>> claimedTargets = new java.util.HashMap<>();
		jade.lang.acl.MessageTemplate mt = jade.lang.acl.MessageTemplate.MatchProtocol("TARGET_CLAIM");
		ACLMessage claimMsg;
		while ((claimMsg = this.myAgent.receive(mt)) != null) {
			String[] data = claimMsg.getContent().split(",");
			if (data.length >= 3) {
				try {
					long ts = Long.parseLong(data[1]);
					int dist = Integer.parseInt(data[2]);
					String sender = claimMsg.getSender().getLocalName();
					if (currentTimestamp - ts < 1000) {
						claimedTargets.put(data[0], new Couple<>(dist, sender));
					}
				} catch (Exception e) {}
			}
		}

		// --- 1. OBSERVATION & MAPPING (Unified) ---
		List<Couple<Location, List<Couple<Observation, String>>>> lobs = ((AbstractDedaleAgent) this.myAgent).observe();

		this.myMap.addNode(myPosition.getLocationId(), MapAttribute.closed);

		boolean newlyExploredNodes = false;

		List<String> obstacles = new ArrayList<>();
		String visibleGolemNode = null;
		String visibleGolemName = null;
		boolean allyVisible = false;

		for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
			Location accessibleNode = obs.getLeft();
			String locId = accessibleNode.getLocationId();

			// Ensure open nodes and edges are mapped, even when hunting
			boolean isNewNode = this.myMap.addNewNode(locId);
			if (isNewNode)
				newlyExploredNodes = true;

			if (!myPosition.getLocationId().equals(locId)) {
				this.myMap.addEdge(myPosition.getLocationId(), locId);
			}

			boolean hasAlly = false;
			String enemy = null;
			for (Couple<Observation, String> o : obs.getRight()) {
				if (o.getLeft() == Observation.AGENTNAME) {
					String name = o.getRight();
					if (this.agentNames != null && this.agentNames.contains(name)) {
						hasAlly = true;
					} else if (this.agentNames != null && !name.equals(this.myAgent.getLocalName())) {
						if (enemy == null || name.compareTo(enemy) < 0) {
							enemy = name;
						}
					}
				} else if (o.getLeft() == Observation.STENCH) {
					int stenchValue = 1;
					try {
						stenchValue = Integer.parseInt(o.getRight());
					} catch (NumberFormatException e) {}
					this.myMap.addStench(locId, stenchValue, currentTimestamp);
					broadcastGolemTrail(locId, stenchValue, currentTimestamp);
				}
			}

			if (hasAlly) {
				obstacles.add(locId);
				allyVisible = true;
			}
			if (enemy != null) {
				if (visibleGolemName == null || enemy.compareTo(visibleGolemName) < 0) {
					visibleGolemNode = locId;
					visibleGolemName = enemy;
				}
			}
		}

		// Add remote siege staff to obstacles
		List<String> siegeStaff = this.myMap.getSiegeStaffLocations();
		for (String s : siegeStaff) {
			if (!obstacles.contains(s) && !s.equals(myPosition.getLocationId())) {
				obstacles.add(s);
			}
		}

		// Clean old stenches and siege status
		this.myMap.cleanOldStenches(1500); // 1.5 sec decay (3 ticks max)
		this.myMap.cleanOldSiegeData(2000); // 2 sec decay

		// Check if exploration is officially done globally
		if (!finishedExploration && !this.myMap.hasOpenNode()) {
			finishedExploration = true;
			System.out.println(this.myAgent.getLocalName() + " - Topology is completely mapped !");
		}

		// --- 2. THE BRAIN: DYNAMIC STATE TOGGLE ---
		// We switch to HUNT if we see the Golem OR if we have very fresh stenches
		// globally.
		Set<String> allStenches = this.myMap.getStenchNodes();
		long maxTimestamp = -1;
		for (String n : allStenches) {
			long ts = this.myMap.getStenchTimestamp(n);
			if (ts > maxTimestamp)
				maxTimestamp = ts;
		}

		boolean isTrailHot = (maxTimestamp != -1 && (currentTimestamp - maxTimestamp) < 4000); // Trail freshness
																								// threshold 4s
		// [FIX-2] Ne pas maintenir le siege s'il n'y a aucune odeur
		boolean isSiegeActive = (this.myMap.getSiegeGolemPos() != null && !allStenches.isEmpty());

		State previousState = this.currentState;
		if (visibleGolemNode != null || isTrailHot || isSiegeActive) {
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
			targetNodeId = computeHuntTarget(myPosition, lobs, obstacles, visibleGolemNode, visibleGolemName,
					allStenches, currentTimestamp, maxTimestamp, allyVisible, claimedTargets);
		} else {
			targetNodeId = computeExploreTarget(myPosition, lobs);
		}

		// --- 4. DEADLOCK DETECTION --- [FIX-4]
		String myPosId = myPosition.getLocationId();
		if (myPosId.equals(this.lastPosition)) {
			this.consecutiveWaitTicks++;
		} else {
			this.consecutiveWaitTicks = 0;
		}
		this.lastPosition = myPosId;

		// Si bloque depuis 3+ ticks, forcer un mouvement aleatoire pour casser le
		// deadlock
		// MAIS PAS si l'agent tient volontairement sa position de siege
		boolean holdingSiegePosition = (this.currentState == State.HUNT
				&& targetNodeId != null && targetNodeId.equals(myPosId));

		if (this.consecutiveWaitTicks >= 3 && !holdingSiegePosition) {
			boolean yielded = false;
			String blockerName = null;
			if (targetNodeId != null && !targetNodeId.equals(myPosId)) {
				// Trouver un allié physiquement sur targetNodeId ou chemin adjacent
				for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
					for (Couple<Observation, String> o : obs.getRight()) {
						if (o.getLeft() == Observation.AGENTNAME && targetNodeId.equals(obs.getLeft().getLocationId())) {
							blockerName = o.getRight();
							break;
						}
					}
					if (blockerName != null) break;
				}
			}
			
			if (blockerName != null && this.myAgent.getLocalName().compareTo(blockerName) > 0) {
				// Priorité FAIBLE : Je dois m'écarter (pas de côté)
				List<String> escapeNodes = new ArrayList<>();
				for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
					String nodeId = obs.getLeft().getLocationId();
					if (!nodeId.equals(myPosId) && !containsAlly(obs.getRight()) && !nodeId.equals(targetNodeId)) {
						escapeNodes.add(nodeId);
					}
				}
				if (!escapeNodes.isEmpty()) {
					Collections.shuffle(escapeNodes);
					targetNodeId = escapeNodes.get(0);
					yielded = true;
				}
			} else if (blockerName != null) {
				// Priorité FORTE : Je maintiens ma cible
				yielded = true; 
			}

			// Fallback blocage fantôme
			if (!yielded) {
				List<String> escapeNodes = new ArrayList<>();
				for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
					String nodeId = obs.getLeft().getLocationId();
					if (!nodeId.equals(myPosId)) {
						escapeNodes.add(nodeId);
					}
				}
				if (!escapeNodes.isEmpty()) {
					Collections.shuffle(escapeNodes);
					targetNodeId = escapeNodes.get(0);
				}
			}
			this.consecutiveWaitTicks = 0;
		}

		// --- 5. EXECUTE MOVEMENT ---
		if (targetNodeId != null) {
			int distToTarget = 0;
			if (!targetNodeId.equals(myPosId)) {
				List<String> bPath = this.myMap.getShortestPath(myPosId, targetNodeId);
				if (bPath != null) distToTarget = bPath.size();
			}
			broadcastMyTarget(targetNodeId, distToTarget);
			if (!targetNodeId.equals(myPosId)) {
				((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(targetNodeId));
			}
		}
	}

	// -----------------------------------------------------------
	// ----------------- EXPLORE LOGIC ---------------------------
	// -----------------------------------------------------------
	private String computeExploreTarget(Location myPosition,
			List<Couple<Location, List<Couple<Observation, String>>>> lobs) {
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
				Collections.shuffle(validPatrolNodes);
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
				if (!alternativeNodeId.equals(myPosition.getLocationId())
						&& !contientAgentsStrict(alternativeNodeId, lobs)) {
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
	private String computeHuntTarget(Location myPosition,
			List<Couple<Location, List<Couple<Observation, String>>>> lobs,
			List<String> obstacles, String visibleGolemNode, String visibleGolemName, Set<String> allStenches,
			long currentTimestamp, long maxTimestamp, boolean allyVisible, java.util.Map<String, Couple<Integer, String>> claimedTargets) {

		String targetNodeId = null;
		String actualGolemPos = null;
		String actualGolemName = null;
		List<String> bestPath = null;

		if (visibleGolemNode != null) {
			actualGolemPos = visibleGolemNode;
			actualGolemName = visibleGolemName;
		}

		String siegeGolemPos = this.myMap.getSiegeGolemPos();
		String siegeGolemName = this.myMap.getSiegeGolemName();

		if (siegeGolemName != null && !allStenches.isEmpty()) {
			if (actualGolemName == null || siegeGolemName.compareTo(actualGolemName) < 0) {
				actualGolemPos = siegeGolemPos;
				actualGolemName = siegeGolemName;
			}
		}

		if (actualGolemPos == null) {
			Set<String> freshestStenches = new HashSet<>();
			if (maxTimestamp != -1) {
				for (String n : allStenches) {
					if (this.myMap.getStenchTimestamp(n) >= maxTimestamp - 100) {
						freshestStenches.add(n);
					}
				}
			}
			Set<String> possibleLocs = this.myMap.getGolemPossibleLocations(freshestStenches);
			// [FIX-1] Exclure notre propre position : le Golem ne peut pas etre sur nous
			if (possibleLocs != null) {
				possibleLocs.remove(myPosition.getLocationId());
			}
			if (possibleLocs != null && possibleLocs.size() == 1) {
				actualGolemPos = possibleLocs.iterator().next();
			} else if (!allStenches.isEmpty() && siegeGolemPos != null) {
				// [FIX-2] Ne faire confiance au siege QUE si on a encore de l'odeur
				actualGolemPos = siegeGolemPos;
				actualGolemName = siegeGolemName;
			}
		}

		if (actualGolemPos != null) {
			List<String> neighborsGol = this.myMap.getNeighbors(actualGolemPos);
			boolean inPosition = neighborsGol.contains(myPosition.getLocationId())
					|| myPosition.getLocationId().equals(actualGolemPos);

			boolean haveLocalStench = false;
			for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
				if (obs.getLeft().getLocationId().equals(myPosition.getLocationId())) {
					for (Couple<Observation, String> o : obs.getRight()) {
						if (o.getLeft() == Observation.STENCH) {
							haveLocalStench = true;
							break;
						}
					}
				}
			}

			// Invalider le siege si un agent est en position (sur le périmètre) et ne sent
			// PAS d'odeur (et ne voit pas le golem)
			if (inPosition && !haveLocalStench && visibleGolemNode == null && actualGolemPos.equals(siegeGolemPos)) {
				this.myMap.updateSiegeStatus(null, null, null, null, null, currentTimestamp + 1);
				actualGolemPos = null; // Invalide le siege !
			}
		}

		if (actualGolemPos != null) {
			List<String> neighbors = this.myMap.getNeighbors(actualGolemPos);
			int A = (this.agentNames != null ? this.agentNames.size() : 0) + 1;
			int D = neighbors.size();

			// Si on est en position
			boolean inPosition = neighbors.contains(myPosition.getLocationId())
					|| myPosition.getLocationId().equals(actualGolemPos);

			Set<String> holes = new HashSet<>(neighbors);
			holes.removeAll(obstacles);
			holes.remove(myPosition.getLocationId());

			// [FIX-5] Ne broadcaster le siege QUE si on a une preuve directe
			boolean confirmedByEvidence = (actualGolemName != null || !allStenches.isEmpty());

			if (inPosition && allyVisible && confirmedByEvidence) {
				broadcastSiegeStatus(actualGolemPos, actualGolemName, String.join(",", holes));
			}

			// [FIX-3] Determiner si on a des allies pour le siege
			boolean hasAllies = allyVisible || !this.myMap.getSiegeStaffLocations().isEmpty();

			// On s'assure que la position actuelle est bien validée par la vue ou un siège
			// actif
			boolean isGolemConfirmed = (actualGolemPos.equals(visibleGolemNode)
					|| actualGolemPos.equals(siegeGolemPos));

			if (inPosition && !myPosition.getLocationId().equals(actualGolemPos) && isGolemConfirmed) {
				// Là, on est sûr de tenir une vraie porte !
				return myPosition.getLocationId();
			} else {
				String bestHole = null;
				int minDist = Integer.MAX_VALUE;

				List<String> sortedHoles = new ArrayList<>(holes);
				if (A < D) {
					sortedHoles.sort((h1, h2) -> Integer.compare(this.myMap.getNeighbors(h2).size(),
							this.myMap.getNeighbors(h1).size()));
				}

				List<String> obstaclesPourContournement = new ArrayList<>(obstacles);
				obstaclesPourContournement.add(actualGolemPos);

				// Mélanger légèrement pour éviter que 3 agents à égale distance
				// choisissent systématiquement le même nœud dans la liste triée.
				Collections.shuffle(sortedHoles);

				for (String hole : sortedHoles) {
					if (obstacles.contains(hole)) continue;

					List<String> path = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), hole,
							obstaclesPourContournement);

					int myDist = (path != null) ? path.size() : Integer.MAX_VALUE;
					
					if (claimedTargets.containsKey(hole) && myDist != Integer.MAX_VALUE) {
						Couple<Integer, String> claim = claimedTargets.get(hole);
						if (claim.getLeft() < myDist) continue;
						if (claim.getLeft() == myDist && this.myAgent.getLocalName().compareTo(claim.getRight()) > 0) continue;
					}

					if (myDist < minDist) {
						minDist = myDist;
						bestHole = hole;
						bestPath = path;
					}
				}

				if (bestHole != null)
					targetNodeId = bestHole;
				else
					targetNodeId = actualGolemPos;
			}

		} else {
			// Fallback Stench Chase : Gradient d'Odeur N2 -> N1
			int minDist = Integer.MAX_VALUE;
			
			String n1 = null;
			long ts1 = -1;
			String n2 = null;
			long ts2 = -1;
			
			for (String loc : allStenches) {
				long ts = this.myMap.getStenchTimestamp(loc);
				if (ts > ts1) {
					ts2 = ts1;
					n2 = n1;
					ts1 = ts;
					n1 = loc;
				} else if (ts > ts2 && ts <= ts1 && !loc.equals(n1)) {
					ts2 = ts;
					n2 = loc;
				}
			}
			
			if (n1 != null) {
				List<String> locNeighbors = this.myMap.getNeighbors(n1);
				
				// Sécurisation du Gradient d'Odeur
				boolean isTrajectoryValid = (n2 != null && locNeighbors.contains(n2));
				
				for (String potentialNextStep : locNeighbors) {
					// Exclure formellement N2 (il vient de là) et les odeurs connues (déjà visitées)
					if ((isTrajectoryValid && potentialNextStep.equals(n2)) || allStenches.contains(potentialNextStep))
						continue;

					List<String> path = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(),
							potentialNextStep, obstacles);
					
					int myDist = (path != null) ? path.size() : Integer.MAX_VALUE;
					
					if (claimedTargets.containsKey(potentialNextStep) && myDist != Integer.MAX_VALUE) {
						Couple<Integer, String> claim = claimedTargets.get(potentialNextStep);
						if (claim.getLeft() < myDist) continue;
						if (claim.getLeft() == myDist && this.myAgent.getLocalName().compareTo(claim.getRight()) > 0) continue;
					}

					if (path != null && !path.isEmpty()) {
						if (myDist < minDist) {
							minDist = myDist;
							targetNodeId = potentialNextStep;
							bestPath = path;
						}
					} else if (potentialNextStep.equals(myPosition.getLocationId())) {
						long ageOfStench = currentTimestamp - ts1;
						if (ageOfStench < 1000) {
							if (0 < minDist) {
								minDist = 0;
								targetNodeId = potentialNextStep;
								bestPath = new ArrayList<>();
							}
						}
					}
				}
				// Si cul-de-sac parfait (n1 n'a que n2 comme voisin), on cible n1 par défaut
				if (targetNodeId == null && !obstacles.contains(n1)) targetNodeId = n1;
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

		// --- Fallback: Explore open nodes closest to the Golem ---
		if (nextNodeId == null) {
			String golemPosToUse = actualGolemPos;
			if (golemPosToUse == null && !allStenches.isEmpty()) {
				long bestTs2 = -1;
				for (String n : allStenches) {
					long ts = this.myMap.getStenchTimestamp(n);
					if (ts > bestTs2) {
						bestTs2 = ts;
						golemPosToUse = n;
					}
				}
			}

			if (golemPosToUse != null) {
				List<String> openNodes = this.myMap.getOpenNodes();
				int minGolemDist = Integer.MAX_VALUE;

				for (String on : openNodes) {
					List<String> pathFromGolem = this.myMap.getShortestPath(golemPosToUse, on);
					if (pathFromGolem != null) {
						List<String> pathToOpen = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), on,
								obstacles);
						if (pathToOpen != null && !pathToOpen.isEmpty() && pathFromGolem.size() < minGolemDist) {
							minGolemDist = pathFromGolem.size();
							nextNodeId = pathToOpen.get(0);
						}
					}
				}
			}
		}

		// --- Ultimate Fallback: Random Walk ---
		if (nextNodeId == null) {
			List<String> validPatrolNodes = new ArrayList<>();
			for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
				String accessibleNodeId = obs.getLeft().getLocationId();
				if (!accessibleNodeId.equals(myPosition.getLocationId()) && !containsAlly(obs.getRight())) {
					validPatrolNodes.add(accessibleNodeId);
				}
			}
			if (!validPatrolNodes.isEmpty()) {
				Collections.shuffle(validPatrolNodes);
				nextNodeId = validPatrolNodes.get(0);
			}
		}

		return nextNodeId;
	}

	// -----------------------------------------------------------
	// -------------------- UTILITIES ----------------------------
	// -----------------------------------------------------------

	private void broadcastGolemTrail(String nodeId, int stenchValue, long timestamp) {
		if (this.agentNames == null || this.agentNames.isEmpty())
			return;
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.setProtocol("GOLEM_TRAIL");
		msg.setSender(this.myAgent.getAID());
		for (String agentName : this.agentNames)
			msg.addReceiver(new AID(agentName, AID.ISLOCALNAME));
		msg.setContent(nodeId + "," + stenchValue + "," + timestamp);
		((AbstractDedaleAgent) this.myAgent).sendMessage(msg);
	}

	private void broadcastSiegeStatus(String golemPos, String golemName, String holesStr) {
		if (this.agentNames == null || this.agentNames.isEmpty())
			return;
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.setProtocol("SIEGE_STATUS");
		msg.setSender(this.myAgent.getAID());
		for (String agentName : this.agentNames)
			msg.addReceiver(new AID(agentName, AID.ISLOCALNAME));
		Location myPos = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
		String myLocStr = myPos != null ? myPos.getLocationId() : "null";
		String rName = golemName != null ? golemName : "null";
		String content = golemPos + ";" + rName + ";" + this.myAgent.getLocalName() + ":" + myLocStr + ";" + holesStr
				+ ";" + System.currentTimeMillis();
		msg.setContent(content);
		((AbstractDedaleAgent) this.myAgent).sendMessage(msg);
	}

	private void broadcastMyTarget(String targetNodeId, int distance) {
		if (this.agentNames == null || this.agentNames.isEmpty() || targetNodeId == null)
			return;
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.setProtocol("TARGET_CLAIM");
		msg.setSender(this.myAgent.getAID());
		for (String agentName : this.agentNames)
			msg.addReceiver(new AID(agentName, AID.ISLOCALNAME));
		msg.setContent(targetNodeId + "," + System.currentTimeMillis() + "," + distance);
		((AbstractDedaleAgent) this.myAgent).sendMessage(msg);
	}

	private boolean containsAlly(List<Couple<Observation, String>> obsList) {
		for (Couple<Observation, String> obs : obsList) {
			if (obs.getLeft() == Observation.AGENTNAME) {
				String name = obs.getRight();
				if (this.agentNames != null && this.agentNames.contains(name))
					return true;
			}
		}
		return false;
	}

	private boolean contientAgentsStrict(String nodeID,
			List<Couple<Location, List<Couple<Observation, String>>>> lobs) {
		for (Couple<Location, List<Couple<Observation, String>>> points : lobs) {
			if (points.getLeft().getLocationId().equals(nodeID)) {
				return containsAlly(points.getRight());
			}
		}
		return false;
	}

	private String getEnemyName(List<Couple<Observation, String>> obsList) {
		String bestEnemy = null;
		for (Couple<Observation, String> obs : obsList) {
			if (obs.getLeft() == Observation.AGENTNAME) {
				String name = obs.getRight();
				if (this.agentNames != null && !this.agentNames.contains(name)
						&& !name.equals(this.myAgent.getLocalName())) {
					if (bestEnemy == null || name.compareTo(bestEnemy) < 0) {
						bestEnemy = name;
					}
				}
			}
		}
		return bestEnemy;
	}

}