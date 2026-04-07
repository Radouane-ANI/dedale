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
	private int consecutiveWaitTicks = 0; // Compteur pour briser les interblocages

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

		// 2. Identify Stenches
		long currentTimestamp = System.currentTimeMillis();

		for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
			String locId = obs.getLeft().getLocationId();

			// MISE À JOUR CRITIQUE DE LA CARTE:
			// Si un agent est arrivé ici via Fallback, sa carte 'g' peut manquer de liens.
			// On force l'ajout du noeud et de l'arête pour que Dijkstra ne plante pas.
			this.myMap.addNode(locId, eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute.closed);
			if (!locId.equals(myPosition.getLocationId())) {
				this.myMap.addEdge(myPosition.getLocationId(), locId);
			}

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
				}
			}
		}

		// 3. Clean old stenches & siege data
		this.myMap.cleanOldStenches(5000); // Temps intermédiaire (5s) pour tolérer un couloir bloqué sans oublier le Golem
		this.myMap.cleanOldSiegeData(3000); // Nettoyer les positions alliées obsolètes

		// 3.5. Identify Obstacles (Adjacent Allies) & Check for Visible GRC
		List<String> obstacles = new ArrayList<>();
		String visibleGolemNode = null;
		boolean allyVisible = false;
		for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
			if (containsAlly(obs.getRight())) {
				obstacles.add(obs.getLeft().getLocationId());
				allyVisible = true;
			}
			String enemy = getEnemyName(obs.getRight());
			if (enemy != null) {
				visibleGolemNode = obs.getLeft().getLocationId();
			}
		}
		
		// Add remote siege staff to obstacles to avoid crowding
		List<String> siegeStaff = this.myMap.getSiegeStaffLocations();
		for(String s : siegeStaff) {
			if(!obstacles.contains(s) && !s.equals(myPosition.getLocationId())) {
				obstacles.add(s);
			}
		}

		// 4. Selection of Target & Siege Coordination
		String nextNodeId = null;
		String targetNodeId = null;
		List<String> bestPath = null;

		String actualGolemPos = null;

		if (visibleGolemNode != null) {
			actualGolemPos = visibleGolemNode;
			System.out.println(this.myAgent.getLocalName() + " - Golem VISUELLEMENT repéré en " + actualGolemPos + " !");
		} else {
			// Triangulation classique
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
					if (this.myMap.getStenchTimestamp(n) >= maxTimestamp - 100) {
						freshestStenches.add(n);
					}
				}
			}

			Set<String> possibleLocs = this.myMap.getGolemPossibleLocations(freshestStenches);
			if (possibleLocs != null && possibleLocs.size() == 1) { // Triangulation parfaite
				actualGolemPos = possibleLocs.iterator().next();
			} else if (this.myMap.getSiegeGolemPos() != null) {
				// Utiliser la position du siège partagée si on l'a
				actualGolemPos = this.myMap.getSiegeGolemPos();
			}
		}

		// Siège Logic
		if (actualGolemPos != null) {
			List<String> neighbors = this.myMap.getNeighbors(actualGolemPos);
			int D = neighbors.size();
			int A = (this.agentNames != null ? this.agentNames.size() : 0) + 1;
			
			// Si on est adjacent au Golem, on est "en poste"
			boolean inPosition = neighbors.contains(myPosition.getLocationId()) || myPosition.getLocationId().equals(actualGolemPos);
			
			Set<String> holes = new HashSet<>(neighbors);
			holes.removeAll(obstacles); // Les obstacles (y compris les alliés du siège connus) bouchent les trous
			holes.remove(myPosition.getLocationId());

			// Broadcast status si on observe un allié proche ou pour informer le reseau
			if (inPosition && allyVisible) {
				broadcastSiegeStatus(actualGolemPos, String.join(",", holes));
			}
			
			if (inPosition && !myPosition.getLocationId().equals(actualGolemPos)) {
				targetNodeId = myPosition.getLocationId(); // stay and block l'issue adjacente
			} else {
				// Il faut choisir un Hole
				String bestHole = null;
				int minDist = Integer.MAX_VALUE;
				
				// Stratégie d'Encerclement par le Degré des Nœuds
				List<String> sortedHoles = new ArrayList<>(holes);
				if (A < D) {
					// Prioriser les trous qui mènent vers des carrefours (degré élevé)
					sortedHoles.sort((h1, h2) -> Integer.compare(this.myMap.getNeighbors(h2).size(), this.myMap.getNeighbors(h1).size()));
				}
				
				// CRUCIAL: On ne doit JAMAIS traverser le Golem pour aller boucher un trou.
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
				
				if (bestHole != null) {
					targetNodeId = bestHole;
				} else {
					targetNodeId = actualGolemPos; // Fallback: visons le golem
				}
			}
		} else {
			// Pas de Golem clair, on chasse à l'odeur (Fallback stenches)
			Set<String> allStenches = this.myMap.getStenchNodes();
			int minDist = Integer.MAX_VALUE;
			long bestTs = -1;

			for (String loc : allStenches) {
				if (loc.equals(myPosition.getLocationId())) continue;
				if (obstacles.contains(loc)) continue; // Ne jamais cibler la cause d'une odeur si un allié y est déjà

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

		if (targetNodeId != null) {
			if (targetNodeId.equals(myPosition.getLocationId())) {
				// We are on the target node! Stay here to block it.
				nextNodeId = myPosition.getLocationId();
			} else {
				// Réutilise le chemin mémorisé pendant la sélection de cible (évite un double calcul Dijkstra).
				// Pour le Golem visible, bestPath est null : on calcule juste ce cas.
				List<String> path = (bestPath != null) ? bestPath
						: this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), targetNodeId, obstacles);
				if (path != null && !path.isEmpty()) {
					if (visibleGolemNode != null && path.get(0).equals(visibleGolemNode)) {
						// Le Golem est SUR la case adjacente. On ne lui marche pas dessus, on bloque !
						nextNodeId = myPosition.getLocationId();
						this.consecutiveWaitTicks = 0; // On est au poste, c'est un arrêt voulu
					} else {
						// Confiance absolue dans le Pathfinding (la carte + Dijkstra)
						// S'il y a un obstacle, le chemin returned serait déjà null.
						nextNodeId = path.get(0);
						this.consecutiveWaitTicks = 0;
					}
				}
			}
		}

		// 5. Fallback Default Patrol
		if (nextNodeId == null) {
			List<String> validPatrolNodes = new ArrayList<>();
			List<String> allPatrolNodes = new ArrayList<>();
			for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
				String accessibleNodeId = obs.getLeft().getLocationId();
				if (!accessibleNodeId.equals(myPosition.getLocationId())) {
					allPatrolNodes.add(accessibleNodeId);
					if (!containsAlly(obs.getRight())) {
						validPatrolNodes.add(accessibleNodeId);
					}
				}
			}
			if (!validPatrolNodes.isEmpty()) {
				java.util.Collections.shuffle(validPatrolNodes);
				nextNodeId = validPatrolNodes.get(0);
			} else if (!allPatrolNodes.isEmpty()) {
				java.util.Collections.shuffle(allPatrolNodes);
				nextNodeId = allPatrolNodes.get(0);
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

	private void broadcastSiegeStatus(String golemPos, String holesStr) {
		if (this.agentNames == null || this.agentNames.isEmpty()) return;

		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.setProtocol("SIEGE_STATUS");
		msg.setSender(this.myAgent.getAID());
		for (String agentName : this.agentNames) {
			msg.addReceiver(new AID(agentName, AID.ISLOCALNAME));
		}
		
		Location myPos = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
		String myLocStr = myPos != null ? myPos.getLocationId() : "null";
		
		// Format: GolemPos;SenderName:SenderPos;Hole1,Hole2;Timestamp
		String content = golemPos + ";" + this.myAgent.getLocalName() + ":" + myLocStr + ";" + holesStr + ";" + System.currentTimeMillis();
		msg.setContent(content);
		((AbstractDedaleAgent) this.myAgent).sendMessage(msg);
	}

	private boolean containsAlly(List<Couple<Observation, String>> obsList) {
		for (Couple<Observation, String> obs : obsList) {
			if (obs.getLeft() == Observation.AGENTNAME) {
				String name = obs.getRight();
				if (this.agentNames != null && this.agentNames.contains(name)) {
					return true;
				}
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