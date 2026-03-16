package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.List;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;

/**
 * The agent periodically share its map.
 * It blindly tries to send all its graph to its friend(s)
 * If it was written properly, this sharing action would NOT be in a ticker
 * behaviour and only a subgraph would be shared.
 * 
 * @author hc
 *
 */
public class ShareMapBehaviour extends TickerBehaviour {


	private MapRepresentation myMap;
	private List<String> receivers;

	/**
	 * The agent periodically share its map.
	 * It blindly tries to send all its graph to its friend(s)
	 * If it was written properly, this sharing action would NOT be in a ticker
	 * behaviour and only a subgraph would be shared.
	 * 
	 * @param a         the agent
	 * @param period    the periodicity of the behaviour (in ms)
	 * @param mymap     (the map to share)
	 * @param receivers the list of agents to send the map to
	 */
	public ShareMapBehaviour(Agent a, long period, MapRepresentation mymap, List<String> receivers) {
		super(a, period);
		this.myMap = mymap;
		this.receivers = receivers;
	}

	/**
	 * 
	 */
	private static final long serialVersionUID = -568863390879327961L;

	@Override
	protected void onTick() {
		if (this.myMap != null && !this.myMap.hasOpenNode()) {
			return; // The map is entirely known, we stop broadcasting MAP.
		}

		// We only send a MAP message to check if the receiver is in communication range.
		// If they are, they will reply with a SYNC-REQ, and we will reply to them.
		// This prevents dropping map deltas into the void when out of range.
		for (String agentName : receivers) {
			ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
			msg.setProtocol("MAP");
			msg.setSender(this.myAgent.getAID());
			msg.addReceiver(new AID(agentName, AID.ISLOCALNAME));
			((AbstractDedaleAgent) this.myAgent).sendMessage(msg);
		}
	}

}
