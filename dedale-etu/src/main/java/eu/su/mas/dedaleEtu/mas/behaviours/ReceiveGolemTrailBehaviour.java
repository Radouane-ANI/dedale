package eu.su.mas.dedaleEtu.mas.behaviours;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

/**
 * Dedicated behaviour to receive GOLEM_TRAIL messages (gossip protocol)
 * and update the agent's map representation with the latest Golem tracks.
 */
public class ReceiveGolemTrailBehaviour extends SimpleBehaviour {

	private static final long serialVersionUID = 1L;
	private MapRepresentation myMap;
	private MessageTemplate msgTemplate;

	public ReceiveGolemTrailBehaviour(AbstractDedaleAgent myagent, MapRepresentation myMap) {
		super(myagent);
		this.myMap = myMap;
		this.msgTemplate = MessageTemplate.MatchProtocol("GOLEM_TRAIL");
	}

	@Override
	public void action() {
		if (this.myMap == null) {
			block(500); // Wait for map initialization
			return;
		}

		ACLMessage msg = this.myAgent.receive(this.msgTemplate);
		if (msg != null) {
			String content = msg.getContent();
			if (content != null) {
				String[] parts = content.split(",");
				if (parts.length == 3) {
					try {
						String nodeId = parts[0];
						int stenchValue = Integer.parseInt(parts[1]);
						long timestamp = Long.parseLong(parts[2]);

						this.myMap.addStench(nodeId, stenchValue, timestamp);
					} catch (NumberFormatException e) {
						System.err.println(
								"ReceiveGolemTrailBehaviour: Error parsing GOLEM_TRAIL msg content: " + content);
					}
				}
			}
		} else {
			block(); // Block until a new message arrives
		}
	}

	@Override
	public boolean done() {
		return false; // Loop forever
	}

	public void setMap(MapRepresentation map) {
		this.myMap = map;
	}
}
