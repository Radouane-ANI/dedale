package eu.su.mas.dedaleEtu.mas.agents.dummies;

import java.util.ArrayList;
import java.util.List;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedale.mas.agent.behaviours.platformManagment.StartMyBehaviours;
import jade.core.behaviours.Behaviour;

public class ControlledAgent extends AbstractDedaleAgent {

	
	
	private static final long serialVersionUID = 728177281660199801L;

	protected void setup(){
		super.setup();

		//get the parameters given into the object[]
		final Object[] args = getArguments();
		//use them as parameters for your behaviours 
		
		List<Behaviour> lb=new ArrayList<Behaviour>();
		//lb.add(new GsControlBehaviour(this,300));
		
		addBehaviour(new StartMyBehaviours(this,lb));

	}


	/**
	 * This method is automatically called after doDelete()
	 */
	protected void takeDown(){
		super.takeDown();
	}
	
	/**
	 * This method is automatically called before migration. 
	 * You can add here all the saving you need
	 */
	protected void beforeMove(){
		super.beforeMove();
	}
	
	/**
	 * This method is automatically called after migration to reload. 
	 * You can add here all the info regarding the state you want your agent to restart from 
	 * 
	 */
	protected void afterMove(){
		super.afterMove();
	}

	
	
	
}
