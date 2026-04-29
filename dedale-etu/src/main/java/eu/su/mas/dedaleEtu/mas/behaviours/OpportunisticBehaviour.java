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
		super(myagent, 400); // 400ms ticks execution
		this.myMap = myMap;
		this.shareBehaviour = shareBehaviour;
		this.receiveTrailBehaviour = receiveTrailBehaviour;
		this.receiveSiegeBehaviour = receiveSiegeBehaviour;
		this.agentNames = agentNames;
	}

	@Override
	public void onTick() {
		initializeMapIfNeeded();

		Location myPosition = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
		if (myPosition == null)
			return;

		long currentTimestamp = System.currentTimeMillis();
		java.util.Map<String, Couple<Integer, Couple<Integer, String>>> claimedTargets = receiveTargetClaims(currentTimestamp);

		// --- 1. OBSERVATION & MAPPING (Unified) ---
		List<Couple<Location, List<Couple<Observation, String>>>> lobs = ((AbstractDedaleAgent) this.myAgent).observe();

		int unattendedStenchesCount = countUnattendedStenches(lobs, myPosition);

		updateMapAndClean(myPosition, lobs, currentTimestamp);

		ObservationData obsData = extractObservationData(myPosition, lobs);

		// --- 2. THE BRAIN: DYNAMIC STATE TOGGLE ---
		long maxTimestamp = calculateMaxStenchTimestamp();
		updateCurrentState(obsData.visibleGolemNode, currentTimestamp, maxTimestamp);

		// --- 3. MOVEMENT DECISION ---
		String targetNodeId = null;
		if (this.currentState == State.HUNT) {
			Set<String> allStenches = this.myMap.getStenchNodes();
			targetNodeId = computeHuntTarget(myPosition, lobs, obsData.obstacles, obsData.visibleGolemNode,
					obsData.visibleGolemName, allStenches, currentTimestamp, maxTimestamp, obsData.allyVisible,
					claimedTargets, unattendedStenchesCount);
		} else {
			targetNodeId = computeExploreTarget(myPosition, lobs);
		}

		// --- 4. DEADLOCK DETECTION --- [FIX-4]
		String myPosId = myPosition.getLocationId();
		updateWaitTicks(myPosId);
		targetNodeId = resolveDeadlockIfNeeded(myPosId, targetNodeId, lobs);

		// --- 5. EXECUTE MOVEMENT ---
		executeMovement(myPosId, targetNodeId, unattendedStenchesCount);
	}

	private void initializeMapIfNeeded() {
		if (this.myMap == null) {
			this.myMap = new MapRepresentation(this.myAgent.getLocalName());
			if (this.shareBehaviour != null)
				this.shareBehaviour.setMap(this.myMap);
			if (this.receiveTrailBehaviour != null)
				this.receiveTrailBehaviour.setMap(this.myMap);
			if (this.receiveSiegeBehaviour != null)
				this.receiveSiegeBehaviour.setMap(this.myMap);
		}
	}

	private java.util.Map<String, Couple<Integer, Couple<Integer, String>>> receiveTargetClaims(long currentTimestamp) {
		java.util.Map<String, Couple<Integer, Couple<Integer, String>>> claimedTargets = new java.util.HashMap<>();
		jade.lang.acl.MessageTemplate mt = jade.lang.acl.MessageTemplate.MatchProtocol("TARGET_CLAIM");
		ACLMessage claimMsg;
		while ((claimMsg = this.myAgent.receive(mt)) != null) {
			String[] data = claimMsg.getContent().split(",");
			if (data.length >= 4) {
				try {
					long ts = Long.parseLong(data[1]);
					int dist = Integer.parseInt(data[2]);
					int count = Integer.parseInt(data[3]);
					String sender = claimMsg.getSender().getLocalName();
					if (currentTimestamp - ts < 1000) {
						claimedTargets.put(data[0], new Couple<>(dist, new Couple<>(count, sender)));
					}
				} catch (Exception e) {
				}
			}
		}
		return claimedTargets;
	}

	private int countUnattendedStenches(List<Couple<Location, List<Couple<Observation, String>>>> lobs, Location myPosition) {
		int unattendedStenchesCount = 0;
		for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
			boolean hasStench = false;
			boolean hasAllyVisible = false;
			for (Couple<Observation, String> o : obs.getRight()) {
				if (o.getLeft() == Observation.STENCH) {
					hasStench = true;
				} else if (o.getLeft() == Observation.AGENTNAME) {
					String name = o.getRight();
					if (this.agentNames != null && this.agentNames.contains(name)) {
						hasAllyVisible = true;
					}
				}
			}
			if (hasStench && !hasAllyVisible && !obs.getLeft().getLocationId().equals(myPosition.getLocationId())) {
				unattendedStenchesCount++;
			}
		}
		return unattendedStenchesCount;
	}

	private void updateMapAndClean(Location myPosition, List<Couple<Location, List<Couple<Observation, String>>>> lobs, long currentTimestamp) {
		this.myMap.addNode(myPosition.getLocationId(), MapAttribute.closed);

		for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
			Location accessibleNode = obs.getLeft();
			String locId = accessibleNode.getLocationId();

			this.myMap.addNewNode(locId);

			if (!myPosition.getLocationId().equals(locId)) {
				this.myMap.addEdge(myPosition.getLocationId(), locId);
			}

			for (Couple<Observation, String> o : obs.getRight()) {
				if (o.getLeft() == Observation.STENCH) {
					int stenchValue = 1;
					try {
						stenchValue = Integer.parseInt(o.getRight());
					} catch (NumberFormatException e) {
					}
					this.myMap.addStench(locId, stenchValue, currentTimestamp);
					broadcastGolemTrail(locId, stenchValue, currentTimestamp);
				}
			}
		}

		this.myMap.cleanOldStenches(1500); // 1.5 sec decay (3 ticks max)
		this.myMap.cleanOldSiegeData(2000); // 2 sec decay

		if (!finishedExploration && !this.myMap.hasOpenNode()) {
			finishedExploration = true;
			System.out.println(this.myAgent.getLocalName() + " - Topology is completely mapped !");
		}
	}

	private static class ObservationData {
		List<String> obstacles = new ArrayList<>();
		String visibleGolemNode = null;
		String visibleGolemName = null;
		boolean allyVisible = false;
	}

	private ObservationData extractObservationData(Location myPosition, List<Couple<Location, List<Couple<Observation, String>>>> lobs) {
		ObservationData data = new ObservationData();

		for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
			String locId = obs.getLeft().getLocationId();
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
				}
			}

			if (hasAlly) {
				data.obstacles.add(locId);
				data.allyVisible = true;
			}
			if (enemy != null) {
				if (data.visibleGolemName == null || enemy.compareTo(data.visibleGolemName) < 0) {
					data.visibleGolemNode = locId;
					data.visibleGolemName = enemy;
				}
			}
		}

		List<String> siegeStaff = this.myMap.getSiegeStaffLocations();
		for (String s : siegeStaff) {
			if (!data.obstacles.contains(s) && !s.equals(myPosition.getLocationId())) {
				data.obstacles.add(s);
			}
		}

		return data;
	}

	private long calculateMaxStenchTimestamp() {
		Set<String> allStenches = this.myMap.getStenchNodes();
		long maxTimestamp = -1;
		for (String n : allStenches) {
			long ts = this.myMap.getStenchTimestamp(n);
			if (ts > maxTimestamp)
				maxTimestamp = ts;
		}
		return maxTimestamp;
	}

	private void updateCurrentState(String visibleGolemNode, long currentTimestamp, long maxTimestamp) {
		Set<String> allStenches = this.myMap.getStenchNodes();
		boolean isTrailHot = (maxTimestamp != -1 && (currentTimestamp - maxTimestamp) < 4000);
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
	}

	private void updateWaitTicks(String myPosId) {
		if (myPosId.equals(this.lastPosition)) {
			this.consecutiveWaitTicks++;
		} else {
			this.consecutiveWaitTicks = 0;
		}
		this.lastPosition = myPosId;
	}

	private String resolveDeadlockIfNeeded(String myPosId, String targetNodeId, List<Couple<Location, List<Couple<Observation, String>>>> lobs) {
		boolean holdingSiegePosition = (this.currentState == State.HUNT
				&& targetNodeId != null && targetNodeId.equals(myPosId));

		if (this.consecutiveWaitTicks >= 3 && !holdingSiegePosition) {
			boolean yielded = false;
			String blockerName = null;
			if (targetNodeId != null && !targetNodeId.equals(myPosId)) {
				for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
					for (Couple<Observation, String> o : obs.getRight()) {
						if (o.getLeft() == Observation.AGENTNAME
								&& targetNodeId.equals(obs.getLeft().getLocationId())) {
							blockerName = o.getRight();
							break;
						}
					}
					if (blockerName != null)
						break;
				}
			}

			if (blockerName != null && this.myAgent.getLocalName().compareTo(blockerName) > 0) {
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
				yielded = true;
			}

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
		return targetNodeId;
	}

	private void executeMovement(String myPosId, String targetNodeId, int unattendedStenchesCount) {
		if (targetNodeId != null) {
			int distToTarget = 0;
			if (!targetNodeId.equals(myPosId)) {
				List<String> bPath = this.myMap.getShortestPath(myPosId, targetNodeId);
				if (bPath != null)
					distToTarget = bPath.size();
			}
			broadcastMyTarget(targetNodeId, distToTarget, unattendedStenchesCount);
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
			long currentTimestamp, long maxTimestamp, boolean allyVisible,
			java.util.Map<String, Couple<Integer, Couple<Integer, String>>> claimedTargets,
			int unattendedStenchesCount) {

		Couple<String, String> golemInfo = determineActualGolemLocation(visibleGolemNode, visibleGolemName, allStenches, maxTimestamp, myPosition);
		String actualGolemPos = validateSiegeAndGetPos(golemInfo.getLeft(), myPosition, lobs, visibleGolemNode, currentTimestamp);
		String actualGolemName = golemInfo.getRight();

		HuntDecision decision;
		if (actualGolemPos != null) {
			decision = computeSiegeCoordination(actualGolemPos, actualGolemName, myPosition, obstacles, allStenches, allyVisible, visibleGolemNode, claimedTargets, unattendedStenchesCount);
		} else {
			decision = computeStenchChaseStrategy(myPosition, allStenches, obstacles, currentTimestamp, claimedTargets, unattendedStenchesCount);
		}

		String nextNodeId = resolveNextNodeId(myPosition, decision, visibleGolemNode, obstacles);
		nextNodeId = applyHuntFallbacks(nextNodeId, myPosition, actualGolemPos, allStenches, obstacles, lobs);

		return nextNodeId;
	}

	private static class HuntDecision {
		String targetNodeId = null;
		List<String> bestPath = null;
	}

	private Couple<String, String> determineActualGolemLocation(String visibleGolemNode, String visibleGolemName, Set<String> allStenches, long maxTimestamp, Location myPosition) {
		String actualGolemPos = null;
		String actualGolemName = null;

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
			if (possibleLocs != null) {
				possibleLocs.remove(myPosition.getLocationId());
			}
			if (possibleLocs != null && possibleLocs.size() == 1) {
				actualGolemPos = possibleLocs.iterator().next();
			} else if (!allStenches.isEmpty() && siegeGolemPos != null) {
				actualGolemPos = siegeGolemPos;
				actualGolemName = siegeGolemName;
			}
		}
		return new Couple<>(actualGolemPos, actualGolemName);
	}

	private String validateSiegeAndGetPos(String actualGolemPos, Location myPosition, List<Couple<Location, List<Couple<Observation, String>>>> lobs, String visibleGolemNode, long currentTimestamp) {
		if (actualGolemPos == null) return null;

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

		String siegeGolemPos = this.myMap.getSiegeGolemPos();
		if (inPosition && !haveLocalStench && visibleGolemNode == null && actualGolemPos.equals(siegeGolemPos)) {
			this.myMap.updateSiegeStatus(null, null, null, null, null, currentTimestamp + 1);
			return null;
		}
		return actualGolemPos;
	}

	private HuntDecision computeSiegeCoordination(String actualGolemPos, String actualGolemName, Location myPosition, List<String> obstacles, Set<String> allStenches, boolean allyVisible, String visibleGolemNode, java.util.Map<String, Couple<Integer, Couple<Integer, String>>> claimedTargets, int unattendedStenchesCount) {
		HuntDecision decision = new HuntDecision();
		List<String> neighbors = this.myMap.getNeighbors(actualGolemPos);
		int A = (this.agentNames != null ? this.agentNames.size() : 0) + 1;
		int D = neighbors.size();

		boolean inPosition = neighbors.contains(myPosition.getLocationId())
				|| myPosition.getLocationId().equals(actualGolemPos);

		Set<String> holes = new HashSet<>(neighbors);
		holes.removeAll(obstacles);
		holes.remove(myPosition.getLocationId());

		boolean confirmedByEvidence = (actualGolemName != null || !allStenches.isEmpty());

		if (inPosition && allyVisible && confirmedByEvidence) {
			broadcastSiegeStatus(actualGolemPos, actualGolemName, String.join(",", holes));
		}

		String siegeGolemPos = this.myMap.getSiegeGolemPos();
		boolean isGolemConfirmed = (actualGolemPos.equals(visibleGolemNode)
				|| actualGolemPos.equals(siegeGolemPos));

		if (inPosition && !myPosition.getLocationId().equals(actualGolemPos) && isGolemConfirmed) {
			decision.targetNodeId = myPosition.getLocationId();
			return decision;
		}

		String bestHole = null;
		int minDist = Integer.MAX_VALUE;

		List<String> sortedHoles = new ArrayList<>(holes);
		if (A < D) {
			sortedHoles.sort((h1, h2) -> Integer.compare(this.myMap.getNeighbors(h2).size(),
					this.myMap.getNeighbors(h1).size()));
		}

		List<String> obstaclesPourContournement = new ArrayList<>(obstacles);
		obstaclesPourContournement.add(actualGolemPos);

		Collections.shuffle(sortedHoles);

		for (String hole : sortedHoles) {
			if (obstacles.contains(hole))
				continue;

			List<String> path = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), hole,
					obstaclesPourContournement);

			int myDist = (path != null) ? path.size() : Integer.MAX_VALUE;

			if (claimedTargets.containsKey(hole) && myDist != Integer.MAX_VALUE) {
				Couple<Integer, Couple<Integer, String>> claim = claimedTargets.get(hole);
				int otherDist = claim.getLeft();
				int otherCount = claim.getRight().getLeft();
				String otherName = claim.getRight().getRight();

				if (otherDist < myDist)
					continue;
				if (otherDist == myDist) {
					if (otherCount < unattendedStenchesCount)
						continue;
					if (otherCount == unattendedStenchesCount && this.myAgent.getLocalName().compareTo(otherName) > 0)
						continue;
				}
			}

			if (myDist < minDist) {
				minDist = myDist;
				bestHole = hole;
				decision.bestPath = path;
			}
		}

		if (bestHole != null)
			decision.targetNodeId = bestHole;
		else
			decision.targetNodeId = actualGolemPos;

		return decision;
	}

	private HuntDecision computeStenchChaseStrategy(Location myPosition, Set<String> allStenches, List<String> obstacles, long currentTimestamp, java.util.Map<String, Couple<Integer, Couple<Integer, String>>> claimedTargets, int unattendedStenchesCount) {
		HuntDecision decision = new HuntDecision();

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
			boolean isTrajectoryValid = (n2 != null && locNeighbors.contains(n2));

			boolean amITheBeater = true;
			if (claimedTargets.containsKey(n1)) {
				Couple<Integer, Couple<Integer, String>> claim = claimedTargets.get(n1);
				List<String> myPathToN1 = this.myMap.getShortestPath(myPosition.getLocationId(), n1);
				int myDistToN1 = (myPathToN1 != null) ? myPathToN1.size() : Integer.MAX_VALUE;

				int otherDist = claim.getLeft();
				int otherCount = claim.getRight().getLeft();
				String otherName = claim.getRight().getRight();

				boolean otherHasPriority = (otherDist < myDistToN1);
				if (otherDist == myDistToN1) {
					if (otherCount < unattendedStenchesCount)
						otherHasPriority = true;
					else if (otherCount == unattendedStenchesCount && this.myAgent.getLocalName().compareTo(otherName) > 0)
						otherHasPriority = true;
				}

				if (otherHasPriority) {
					amITheBeater = false;
				}
			}

			if (amITheBeater) {
				decision = computeBeaterStrategy(myPosition, n1, n2, isTrajectoryValid, allStenches, locNeighbors, obstacles, currentTimestamp, ts1, claimedTargets, unattendedStenchesCount);
			} else {
				decision = computeInterceptorStrategy(myPosition, n1, n2, obstacles, claimedTargets);
			}

			if (decision.targetNodeId == null && !obstacles.contains(n1))
				decision.targetNodeId = n1;
		}

		return decision;
	}

	private HuntDecision computeBeaterStrategy(Location myPosition, String n1, String n2, boolean isTrajectoryValid, Set<String> allStenches, List<String> locNeighbors, List<String> obstacles, long currentTimestamp, long ts1, java.util.Map<String, Couple<Integer, Couple<Integer, String>>> claimedTargets, int unattendedStenchesCount) {
		HuntDecision decision = new HuntDecision();
		int minDist = Integer.MAX_VALUE;

		for (String potentialNextStep : locNeighbors) {
			if ((isTrajectoryValid && potentialNextStep.equals(n2))
					|| allStenches.contains(potentialNextStep))
				continue;

			List<String> path = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(),
					potentialNextStep, obstacles);

			int myDist = (path != null) ? path.size() : Integer.MAX_VALUE;

			if (claimedTargets.containsKey(potentialNextStep) && myDist != Integer.MAX_VALUE) {
				Couple<Integer, Couple<Integer, String>> claim = claimedTargets.get(potentialNextStep);
				int otherDist = claim.getLeft();
				int otherCount = claim.getRight().getLeft();
				String otherName = claim.getRight().getRight();

				if (otherDist < myDist)
					continue;
				if (otherDist == myDist) {
					if (otherCount < unattendedStenchesCount)
						continue;
					if (otherCount == unattendedStenchesCount && this.myAgent.getLocalName().compareTo(otherName) > 0)
						continue;
				}
			}

			if (path != null && !path.isEmpty()) {
				if (myDist < minDist) {
					minDist = myDist;
					decision.targetNodeId = potentialNextStep;
					decision.bestPath = path;
				}
			} else if (potentialNextStep.equals(myPosition.getLocationId())) {
				long ageOfStench = currentTimestamp - ts1;
				if (ageOfStench < 1000) {
					if (0 < minDist) {
						minDist = 0;
						decision.targetNodeId = potentialNextStep;
						decision.bestPath = new ArrayList<>();
					}
				}
			}
		}
		return decision;
	}

	private HuntDecision computeInterceptorStrategy(Location myPosition, String n1, String n2, List<String> obstacles, java.util.Map<String, Couple<Integer, Couple<Integer, String>>> claimedTargets) {
		HuntDecision decision = new HuntDecision();
		List<String> avoidNodes = new ArrayList<>(obstacles);
		avoidNodes.add(n1);
		if (n2 != null)
			avoidNodes.add(n2);

		boolean interceptionFound = false;

		for (int depth = 2; depth <= 5; depth++) {
			String interceptionTarget = predictGolemInterceptionPoint(n1,
					n2 != null ? n2 : myPosition.getLocationId(), depth);

			if (interceptionTarget != null && !interceptionTarget.equals(myPosition.getLocationId())) {
				List<String> path = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(),
						interceptionTarget, avoidNodes);

				if (path != null && !path.isEmpty()) {
					if (path.size() <= depth + 1) {
						decision.targetNodeId = interceptionTarget;
						decision.bestPath = path;
						interceptionFound = true;
						break;
					}
				}
			}
		}

		if (!interceptionFound) {
			List<String> n1Neighbors = this.myMap.getNeighbors(n1);
			List<String> availablePerimeter = new ArrayList<>();

			for (String v : n1Neighbors) {
				if (!obstacles.contains(v) && !v.equals(n2)) {
					if (!claimedTargets.containsKey(v)) {
						availablePerimeter.add(v);
					}
				}
			}

			if (!availablePerimeter.isEmpty()) {
				int minDistToPerim = Integer.MAX_VALUE;
				for (String p : availablePerimeter) {
					List<String> pPath = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), p,
							obstacles);
					if (pPath != null && pPath.size() < minDistToPerim) {
						minDistToPerim = pPath.size();
						decision.targetNodeId = p;
						decision.bestPath = pPath;
					}
				}
			}

			if (decision.targetNodeId == null) {
				decision.targetNodeId = n1;
				decision.bestPath = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), n1, obstacles);
			}
		}
		return decision;
	}

	private String resolveNextNodeId(Location myPosition, HuntDecision decision, String visibleGolemNode, List<String> obstacles) {
		String nextNodeId = null;
		if (decision.targetNodeId != null) {
			if (decision.targetNodeId.equals(myPosition.getLocationId())) {
				nextNodeId = myPosition.getLocationId();
			} else {
				List<String> path = (decision.bestPath != null) ? decision.bestPath
						: this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), decision.targetNodeId, obstacles);
				if (path != null && !path.isEmpty()) {
					if (visibleGolemNode != null && path.get(0).equals(visibleGolemNode)) {
						nextNodeId = myPosition.getLocationId();
					} else {
						nextNodeId = path.get(0);
					}
				}
			}
		}
		return nextNodeId;
	}

	private String applyHuntFallbacks(String nextNodeId, Location myPosition, String actualGolemPos, Set<String> allStenches, List<String> obstacles, List<Couple<Location, List<Couple<Observation, String>>>> lobs) {
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

	private void broadcastMyTarget(String targetNodeId, int distance, int count) {
		if (this.agentNames == null || this.agentNames.isEmpty() || targetNodeId == null)
			return;
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.setProtocol("TARGET_CLAIM");
		msg.setSender(this.myAgent.getAID());
		for (String agentName : this.agentNames)
			msg.addReceiver(new AID(agentName, AID.ISLOCALNAME));
		msg.setContent(targetNodeId + "," + System.currentTimeMillis() + "," + distance + "," + count);
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

	/**
	 * Projette la position du Golem N steps dans le futur en supposant qu'il fuit
	 * l'odeur/les agents.
	 * 
	 * @param currentNode  Position actuelle (ou odeur la plus fraîche)
	 * @param previousNode Position précédente du Golem
	 * @param depthAhead   Nombre de pas de projection
	 * @return Le nœud prédit
	 */
	private String predictGolemInterceptionPoint(String currentNode, String previousNode, int depthAhead) {
		String predictedNode = currentNode;
		String cameFrom = previousNode;

		Set<String> simulatedVisited = new HashSet<>();
		simulatedVisited.add(cameFrom);
		simulatedVisited.add(currentNode);

		for (int i = 0; i < depthAhead; i++) {
			List<String> neighbors = this.myMap.getNeighbors(predictedNode);

			// On retire les cases d'où il vient pour forcer le mouvement vers l'avant
			neighbors.removeAll(simulatedVisited);

			if (neighbors.isEmpty()) {
				// Cul-de-sac ou impasse topologique, le Golem s'arrêtera ici
				break;
			}

			// Stratégie de fuite : le Golem a tendance à aller vers les zones ouvertes.
			// On trie les voisins pour prendre celui qui a le plus de connexions (le plus
			// grand potentiel de fuite)
			neighbors.sort((n1, n2) -> Integer.compare(this.myMap.getNeighbors(n2).size(),
					this.myMap.getNeighbors(n1).size()));

			cameFrom = predictedNode;
			predictedNode = neighbors.get(0); // On simule qu'il prend le chemin le plus ouvert
			simulatedVisited.add(predictedNode);
		}

		return predictedNode;
	}

}