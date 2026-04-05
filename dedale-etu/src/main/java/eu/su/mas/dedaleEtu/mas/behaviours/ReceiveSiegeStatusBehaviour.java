package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

/**
 * Dedicated behaviour to receive SIEGE_STATUS messages
 * and update the agent's map representation with the latest Siege data.
 */
public class ReceiveSiegeStatusBehaviour extends SimpleBehaviour {

	private static final long serialVersionUID = 1L;
	private MapRepresentation myMap;
	private MessageTemplate msgTemplate;

	public ReceiveSiegeStatusBehaviour(AbstractDedaleAgent myagent, MapRepresentation myMap) {
		super(myagent);
		this.myMap = myMap;
		this.msgTemplate = MessageTemplate.MatchProtocol("SIEGE_STATUS");
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
				// Format attendu: GolemPos;SenderName:SenderPos;Hole1,Hole2;Timestamp
				String[] parts = content.split(";");
				if (parts.length >= 4) {
					try {
						String golemPos = parts[0];
						if(golemPos.equals("null")) golemPos = null;
						
						String senderInfo = parts[1];
						String senderName = null;
						String senderPos = null;
						if (senderInfo.contains(":")) {
							String[] sparts = senderInfo.split(":");
							senderName = sparts[0];
							senderPos = sparts[1];
						}

						Set<String> holes = new HashSet<>();
						if (!parts[2].isEmpty() && !parts[2].equals("null")) {
							holes.addAll(Arrays.asList(parts[2].split(",")));
						}

						long timestamp = Long.parseLong(parts[3]);

						this.myMap.updateSiegeStatus(golemPos, senderName, senderPos, holes, timestamp);
					} catch (Exception e) {
						System.err.println("ReceiveSiegeStatusBehaviour: Error parsing SIEGE_STATUS msg content: " + content);
						e.printStackTrace();
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
