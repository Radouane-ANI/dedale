package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.Iterator;
import java.util.List;

import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;

import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;

import jade.core.behaviours.SimpleBehaviour;

/**
 * <pre>
 * This behaviour allows an agent to explore the environment and learn the associated topological map.
 * The algorithm is a pseudo - DFS computationally consuming because its not optimised at all.
 * 
 * When all the nodes around him are visited, the agent randomly select an open node and go there to restart its dfs. 
 * This (non optimal) behaviour is done until all nodes are explored. 
 * 
 * Warning, this behaviour does not save the content of visited nodes, only the topology.
 * Warning, the map sharing logic has been moved to ShareMapFSMBehaviour.
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

	/** Reference to the FSMBehaviour for lazy map init */
	private ShareMapFSMBehaviour shareBehaviour;
	
	private ReceiveGolemTrailBehaviour receiveTrailBehaviour;

	/**
	 * 
	 * @param myagent         reference to the agent we are adding this behaviour to
	 * @param myMap           known map of the world the agent is living in
	 * @param agentNames      name of the agents to share the map with
	 * @param shareBehaviour  reference to the ShareMapFSMBehaviour (for lazy map init)
	 */
	public ExploCoopBehaviour(final AbstractDedaleAgent myagent, MapRepresentation myMap,
			ShareMapFSMBehaviour shareBehaviour, ReceiveGolemTrailBehaviour receiveTrailBehaviour) {
		super(myagent);
		this.myMap = myMap;
		this.shareBehaviour = shareBehaviour;
		this.receiveTrailBehaviour = receiveTrailBehaviour;
	}

	@Override
	public void action() {

		if (this.myMap == null) {
			this.myMap = new MapRepresentation(this.myAgent.getLocalName());
			// Also pass the map to the share behaviour
			if (this.shareBehaviour != null) {
				this.shareBehaviour.setMap(this.myMap);
			}
			if (this.receiveTrailBehaviour != null) {
				this.receiveTrailBehaviour.setMap(this.myMap);
			}
		}

		// 0) Retrieve the current position
		Location myPosition = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
		String nextNodeId = null;

		if (myPosition != null) {

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

			// 3) select next move.
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
				// 3.1 If there exist one open node directly reachable, go for it,
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
