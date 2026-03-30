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

		// 3. Clean old stenches
		this.myMap.cleanOldStenches(5000); // Temps intermédiaire (5s) pour tolérer un couloir bloqué sans oublier le
											// Golem

		// 3.5. Identify Obstacles (Adjacent Allies) & Check for Visible GRC
		List<String> obstacles = new ArrayList<>();
		String visibleGolemNode = null;
		for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
			if (containsAlly(obs.getRight())) {
				obstacles.add(obs.getLeft().getLocationId());
			}
			String enemy = getEnemyName(obs.getRight());
			if (enemy != null) {
				visibleGolemNode = obs.getLeft().getLocationId();
			}
		}

		// 4. Selection of Target
		String nextNodeId = null;
		String targetNodeId = null;
		List<String> bestPath = null; // Mémorise le chemin vers targetNodeId pour éviter un double calcul Dijkstra

		if (visibleGolemNode != null) {
			// Le Golem est en contact visuel direct, on abandonne la triangulation !
			targetNodeId = visibleGolemNode;
			System.out.println(this.myAgent.getLocalName() + " - Golem VISUELLEMENT repéré en " + targetNodeId
					+ " ! Je le poursuis !");
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
					// Group stenches ONLY if almost simultaneous (100ms) to avoid mixing different
					// Golem steps !
					if (this.myMap.getStenchTimestamp(n) >= maxTimestamp - 100) {
						freshestStenches.add(n);
					}
				}
			}

			Set<String> possibleLocs = this.myMap.getGolemPossibleLocations(freshestStenches);

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
						bestPath = path; // mémorise le chemin pour ne pas le recalculer
					}
				}

				// If we are literally on the location, maybe the Golem is here or we collided.
				if (targetNodeId == null && possibleLocs.contains(myPosition.getLocationId())) {
					targetNodeId = myPosition.getLocationId(); // we are already on the best node or no path
				}
			} else {
				// Fallback: If Triangulation yields empty, follow the ABSOLUTELY CURRENT
				// FRESHEST part of the trail!
				int minDist = Integer.MAX_VALUE;
				long bestTs = -1;

				for (String loc : allStenches) {
					if (loc.equals(myPosition.getLocationId()))
						continue;

					long ts = this.myMap.getStenchTimestamp(loc);
					List<String> path = this.myMap.getShortestPathAvoiding(myPosition.getLocationId(), loc, obstacles);

					if (path != null && !path.isEmpty()) {
						// Prioriser d'ABORD l'odeur la plus récente (pour ne pas remonter la piste à
						// l'envers)
						if (ts > bestTs || (ts == bestTs && path.size() < minDist)) {
							bestTs = ts;
							minDist = path.size();
							targetNodeId = loc;
							bestPath = path; // mémorise le chemin pour ne pas le recalculer
						}
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
						nextNodeId = path.get(0);
						if (obstacles.contains(nextNodeId)) {
							// Si la route est bloquée par un ami, on incremente le compteur d'interblocage
							this.consecutiveWaitTicks++;
							if (this.consecutiveWaitTicks > 4) {
								// Ça fait trop longtemps qu'on est bloqué par un allié (2 sec)
								// On force un petit "nudge" aléatoire pour casser le verrou
								System.out.println(this.myAgent.getLocalName() + " - INTERBLOCAGE ALLIÉ sur "
										+ nextNodeId + ". Je me décale !");
								nextNodeId = null; // Déclenche Fallback Patrol
							} else {
								nextNodeId = myPosition.getLocationId(); // Attente courtoise
							}
						} else {
							// Mouvement normal, on reset le compteur
							this.consecutiveWaitTicks = 0;
						}
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
