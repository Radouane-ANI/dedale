package eu.su.mas.dedaleEtu.mas.agents.dummies.explo;

import java.util.ArrayList;
import java.util.List;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedale.mas.agent.behaviours.platformManagment.*;


import eu.su.mas.dedaleEtu.mas.behaviours.ShareMapFSMBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.OpportunisticBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.ReceiveGolemTrailBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.ReceiveSiegeStatusBehaviour;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;

import jade.core.behaviours.Behaviour;

/**
 * <pre>
 * ExploreCoop agent. 
 * Basic example of how to "collaboratively" explore the map
 *  - It explore the map using a DFS algorithm and blindly tries to share the topology with the agents within reach.
 *  - The shortestPath computation is not optimized
 *  - Agents do not coordinate themselves on the node(s) to visit, thus progressively creating a single file. It's bad.
 *  - The agent sends all its map, periodically, forever. Its bad x3.
 *  - You should give him the list of agents'name to send its map to in parameter when creating the agent.
 *   Object [] entityParameters={"Name1","Name2};
 *   ag=createNewDedaleAgent(c, agentName, ExploreCoopAgent.class.getName(), entityParameters);
 *  
 * It stops when all nodes have been visited.
 * 
 * 
 * </pre>
 * 
 * @author hc
 *
 */

public class ExploreCoopAgent extends AbstractDedaleAgent {

	private static final long serialVersionUID = -7969469610241668140L;
	private MapRepresentation myMap;

	/**
	 * This method is automatically called when "agent".start() is executed.
	 * Consider that Agent is launched for the first time.
	 * 1) set the agent attributes
	 * 2) add the behaviours
	 * 
	 */
	protected void setup() {

		super.setup();

		// get the parameters added to the agent at creation (if any)
		final Object[] args = getArguments();

		
		List<String> list_agentNames = new ArrayList<String>();
		boolean hasHumanGolem = false;
		if (args.length == 0) {
			System.err.println("Error while creating the agent, names of agent to contact expected");
			System.exit(-1);
		} else {
			int i = 2;
			// The last argument is now the hasHumanGolem flag
			while (i < args.length - 1) {
				list_agentNames.add((String) args[i]);
				i++;
			}
			hasHumanGolem = (Boolean) args[args.length - 1];
		}
		System.out.println(this.getLocalName() + " - hasHumanGolem=" + hasHumanGolem + ", receivers=" + list_agentNames);

		List<Behaviour> lb = new ArrayList<Behaviour>();

		/************************************************
		 * 
		 * ADD the behaviours of the Dummy Moving Agent
		 * 
		 ************************************************/

		ShareMapFSMBehaviour shareBehaviour = new ShareMapFSMBehaviour(this, this.myMap, list_agentNames);
		lb.add(shareBehaviour);

		ReceiveGolemTrailBehaviour receiveTrailBehaviour = new ReceiveGolemTrailBehaviour(this, this.myMap);
		lb.add(receiveTrailBehaviour);
		
		ReceiveSiegeStatusBehaviour receiveSiegeBehaviour = new ReceiveSiegeStatusBehaviour(this, this.myMap);
		lb.add(receiveSiegeBehaviour);

		lb.add(new OpportunisticBehaviour(this, this.myMap, shareBehaviour, receiveTrailBehaviour, receiveSiegeBehaviour, list_agentNames, hasHumanGolem));

		addBehaviour(new StartMyBehaviours(this, lb));

		System.out.println("the  agent " + this.getLocalName() + " is started");

	}

	/**
	 * This method is automatically called after doDelete()
	 */
	protected void takeDown() {
		super.takeDown();
	}

	protected void beforeMove() {
		super.beforeMove();
		// System.out.println("I migrate");
	}

	protected void afterMove() {
		super.afterMove();
		// System.out.println("I migrated");
	}

}
