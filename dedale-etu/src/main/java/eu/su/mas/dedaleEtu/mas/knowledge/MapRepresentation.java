package eu.su.mas.dedaleEtu.mas.knowledge;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.stream.Collectors;

import org.graphstream.algorithm.Dijkstra;
import org.graphstream.graph.Edge;
import org.graphstream.graph.EdgeRejectedException;
import org.graphstream.graph.ElementNotFoundException;
import org.graphstream.graph.Graph;
import org.graphstream.graph.IdAlreadyInUseException;
import org.graphstream.graph.Node;
import org.graphstream.graph.implementations.SingleGraph;
import org.graphstream.ui.fx_viewer.FxViewPanel;
import org.graphstream.ui.fx_viewer.FxViewer;
import org.graphstream.ui.javafx.FxGraphRenderer;
import org.graphstream.ui.view.Viewer;
import dataStructures.serializableGraph.*;
import dataStructures.tuple.Couple;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * This simple topology representation only deals with the graph, not its
 * content.</br>
 * The knowledge representation is not well written (at all), it is just given
 * as a minimal example.</br>
 * The viewer methods are not independent of the data structure, and the
 * dijkstra is recomputed every-time.
 * 
 * @author hc
 */
public class MapRepresentation implements Serializable {

	/**
	 * A node is open, closed, or agent
	 * 
	 * @author hc
	 *
	 */

	public enum MapAttribute {
		agent, open, closed;

	}

	private static final long serialVersionUID = -1333959882640838272L;

	/*********************************
	 * Parameters for graph rendering
	 ********************************/

	private String defaultNodeStyle = "node {" + "fill-color: black;"
			+ " size-mode:fit;text-alignment:under; text-size:14;text-color:white;text-background-mode:rounded-box;text-background-color:black;}";
	private String nodeStyle_open = "node.open {" + "fill-color: forestgreen;" + "}";
	private String nodeStyle_agent = "node.agent {" + "fill-color: blue;" + "}";
	private String nodeStyle = defaultNodeStyle + nodeStyle_agent + nodeStyle_open;

	private Graph g; // data structure non serializable
	private Viewer viewer; // ref to the display, non serializable
	private Integer nbEdges;// used to generate the edges ids
	private String agentName;// name of the agent the map belongs to

	private HashMap<String, Set<String>> knownNodesByAgent = new HashMap<>(); // Which nodes does another agent already
																				// know?
	private HashMap<String, Set<String>> knownEdgesByAgent = new HashMap<>(); // Which edges does another agent already
																				// know? (Stored as A-B)

	private HashMap<String, Integer> stenches = new HashMap<>();
	private HashMap<String, Long> stenchTimestamps = new HashMap<>();

	// --- Siege Protocol Data ---
	private String siegeGolemPos = null;
	private String siegeGolemName = null;
	private long siegeTimestamp = 0;
	private HashMap<String, String> siegeStaff = new HashMap<>();
	private HashMap<String, Long> siegeStaffTimestamps = new HashMap<>();
	private Set<String> siegeHoles = new HashSet<>();

	private SerializableSimpleGraph<String, MapAttribute> sg;// used as a temporary dataStructure during migration
	private int updateCount = 0;

	public int getUpdateCount() {
		return updateCount;
	}

	/**
	 * Creates a standard edge identifier so we track "A-B" identically to "B-A"
	 */
	private String getStandardizedEdgeId(String node1, String node2) {
		if (node1.compareTo(node2) < 0) {
			return node1 + "-" + node2;
		}
		return node2 + "-" + node1;
	}

	/**
	 * @deprecated Prefer the use of MapRepresentation(String agentName)
	 */
	@Deprecated
	public MapRepresentation() {

		// System.setProperty("org.graphstream.ui.renderer","org.graphstream.ui.j2dviewer.J2DGraphRenderer");
		System.setProperty("org.graphstream.ui", "javafx");
		this.g = new SingleGraph("My world vision");
		this.g.setAttribute("ui.stylesheet", nodeStyle);

		Platform.runLater(() -> {
			// openGui();
			openGui4();
		});
		// this.viewer = this.g.display();

		this.nbEdges = 0;
	}

	/**
	 * @param agentName Name of the agent this representation belongs too
	 */
	public MapRepresentation(String agentName) {
		// System.setProperty("org.graphstream.ui.renderer","org.graphstream.ui.j2dviewer.J2DGraphRenderer");
		System.setProperty("org.graphstream.ui", "javafx");
		this.g = new SingleGraph("My world vision");
		this.g.setAttribute("ui.stylesheet", nodeStyle);
		this.agentName = agentName;

		Platform.runLater(() -> {
			// openGui();
			openGui4();

		});
		this.nbEdges = 0;
	}

	/**
	 * Add or replace a node and its attribute
	 * 
	 * @param id           unique identifier of the node
	 * @param mapAttribute attribute to process
	 */
	public synchronized void addNode(String id, MapAttribute mapAttribute) {
		Node n;
		if (this.g.getNode(id) == null) {
			n = this.g.addNode(id);
		} else {
			n = this.g.getNode(id);
		}
		String currentAttr = (String) n.getAttribute("ui.class");
		if (currentAttr == null || !currentAttr.equals(mapAttribute.toString())) {
			n.clearAttributes();
			n.setAttribute("ui.class", mapAttribute.toString());
			n.setAttribute("ui.label", id);
			this.updateCount++;
		}
	}

	/**
	 * Add a node to the graph. Do nothing if the node already exists.
	 * If new, it is labeled as open (non-visited)
	 * 
	 * @param id id of the node
	 * @return true if added
	 */
	public synchronized boolean addNewNode(String id) {
		if (this.g.getNode(id) == null) {
			addNode(id, MapAttribute.open);
			return true;
		}
		return false;
	}

	/**
	 * Add an undirect edge if not already existing.
	 * 
	 * @param idNode1 unique identifier of node1
	 * @param idNode2 unique identifier of node2
	 */
	public synchronized void addEdge(String idNode1, String idNode2) {
		this.nbEdges++;
		try {
			Edge e = this.g.addEdge(this.nbEdges.toString(), idNode1, idNode2);
			if (e != null) {
				e.setAttribute("weight", 1.0);
			}
			this.updateCount++;
		} catch (IdAlreadyInUseException e1) {
			System.err.println("ID existing");
			System.exit(1);
		} catch (EdgeRejectedException e2) {
			this.nbEdges--;
		} catch (ElementNotFoundException e3) {
		}
	}

	/**
	 * Compute the shortest Path from idFrom to IdTo. The computation is currently
	 * not very efficient
	 * 
	 * 
	 * @param idFrom id of the origin node
	 * @param idTo   id of the destination node
	 * @return the list of nodes to follow, null if the targeted node is not
	 *         currently reachable
	 */
	public synchronized List<String> getShortestPath(String idFrom, String idTo) {
		List<String> shortestPath = new ArrayList<String>();

		Node sourceNode = g.getNode(idFrom);
		Node destNode = g.getNode(idTo);
		if (sourceNode == null || destNode == null) {
			return null;
		}

		Dijkstra dijkstra = new Dijkstra();// number of edge
		dijkstra.init(g);
		dijkstra.setSource(sourceNode);
		dijkstra.compute();// compute the distance to all nodes from idFrom
		List<Node> path = dijkstra.getPath(destNode).getNodePath(); // the shortest path from idFrom to idTo
		Iterator<Node> iter = path.iterator();
		while (iter.hasNext()) {
			shortestPath.add(iter.next().getId());
		}
		dijkstra.clear();
		if (shortestPath.isEmpty()) {// The openNode is not currently reachable
			return null;
		} else {
			shortestPath.remove(0);// remove the current position
		}
		return shortestPath;
	}

	public synchronized List<String> getShortestPathAvoiding(String idFrom, String idTo, List<String> nodesToAvoid) {
		List<String> shortestPath = new ArrayList<String>();

		Node sourceNode = g.getNode(idFrom);
		Node destNode = g.getNode(idTo);
		if (sourceNode == null || destNode == null) {
			return null;
		}

		// GraphStream doesn't inherently penalize nodes, so we penalize edges leading
		// to/from obstacles.
		try {
			// Temporarily assign high weight to edges connected to avoided nodes
			if (nodesToAvoid != null) {
				for (String avoidId : nodesToAvoid) {
					Node n = this.g.getNode(avoidId);
					if (n != null) {
						n.edges().forEach(e -> e.setAttribute("weight", 999999.0));
					}
				}
			}

			Dijkstra dijkstra = new Dijkstra(Dijkstra.Element.EDGE, "result", "weight");
			dijkstra.init(g);
			dijkstra.setSource(sourceNode);
			dijkstra.compute();

			if (destNode != null) {
				List<Node> path = dijkstra.getPath(destNode).getNodePath();
				Iterator<Node> iter = path.iterator();
				while (iter.hasNext()) {
					shortestPath.add(iter.next().getId());
				}
			}
			dijkstra.clear();

		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			// Restore edge weights
			if (nodesToAvoid != null) {
				for (String avoidId : nodesToAvoid) {
					Node n = this.g.getNode(avoidId);
					if (n != null) {
						n.edges().forEach(e -> e.setAttribute("weight", 1.0));
					}
				}
			}
		}

		if (shortestPath.isEmpty()) {
			return null;
		} else {
			// Vérification stricte : si le chemin physiquement trouvé passe par un
			// obstacle, on le coupe.
			// (Car Dijkstra avec simple pénalité le renvoyait quand même en absence
			// d'alternative)
			if (nodesToAvoid != null) {
				for (int i = 1; i < shortestPath.size() - 1; i++) {
					if (nodesToAvoid.contains(shortestPath.get(i))) {
						return null; // Aucun chemin réellement libre
					}
				}
				// On vérifie aussi le tout premier pas
				if (shortestPath.size() > 1 && nodesToAvoid.contains(shortestPath.get(1))
						&& !shortestPath.get(1).equals(idTo)) {
					return null;
				}
			}
			shortestPath.remove(0); // remove origin
		}
		return shortestPath;
	}

	public List<String> getShortestPathToClosestOpenNode(String myPosition) {
		// 1) Get all openNodes
		List<String> opennodes = getOpenNodes();

		// 2) select the closest one
		List<Couple<String, Integer>> lc = opennodes.stream()
				.map(on -> (getShortestPath(myPosition, on) != null)
						? new Couple<String, Integer>(on, getShortestPath(myPosition, on).size())
						: new Couple<String, Integer>(on, Integer.MAX_VALUE))// some nodes my be unreachable if the
																				// agents do not share at least one
																				// common node.
				.collect(Collectors.toList());

		Optional<Couple<String, Integer>> closest = lc.stream().min(Comparator.comparing(Couple::getRight));
		// 3) Compute shorterPath

		return getShortestPath(myPosition, closest.get().getLeft());
	}

	public List<String> getOpenNodes() {
		return this.g.nodes()
				.filter(x -> x.getAttribute("ui.class") == MapAttribute.open.toString())
				.map(Node::getId)
				.collect(Collectors.toList());
	}

	/**
	 * Before the migration we kill all non serializable components and store their
	 * data in a serializable form
	 */
	public void prepareMigration() {
		serializeGraphTopology();

		closeGui();

		this.g = null;
	}

	/**
	 * Before sending the agent knowledge of the map it should be serialized.
	 */
	private void serializeGraphTopology() {
		this.sg = new SerializableSimpleGraph<String, MapAttribute>();
		Iterator<Node> iter = this.g.iterator();
		while (iter.hasNext()) {
			Node n = iter.next();
			sg.addNode(n.getId(), MapAttribute.valueOf((String) n.getAttribute("ui.class")));
		}
		Iterator<Edge> iterE = this.g.edges().iterator();
		while (iterE.hasNext()) {
			Edge e = iterE.next();
			Node sn = e.getSourceNode();
			Node tn = e.getTargetNode();
			sg.addEdge(e.getId(), sn.getId(), tn.getId());
		}
	}

	public synchronized SerializableSimpleGraph<String, MapAttribute> getSerializableGraph() {
		serializeGraphTopology();
		return this.sg;
	}

	/**
	 * Extract only the nodes and edges that the given receiver agent does not yet
	 * know about.
	 * Returns null if there are no new nodes or edges to share.
	 */
	public synchronized SerializableSimpleGraph<String, MapAttribute> getMapDelta(String receiverName) {
		boolean hasDelta = false;
		SerializableSimpleGraph<String, MapAttribute> deltaSg = new SerializableSimpleGraph<String, MapAttribute>();

		Set<String> knownNodes = knownNodesByAgent.computeIfAbsent(receiverName, k -> new HashSet<>());
		Set<String> knownEdges = knownEdgesByAgent.computeIfAbsent(receiverName, k -> new HashSet<>());

		// Check nodes
		Iterator<Node> iter = this.g.iterator();
		while (iter.hasNext()) {
			Node n = iter.next();
			if (!knownNodes.contains(n.getId())) {
				deltaSg.addNode(n.getId(), MapAttribute.valueOf((String) n.getAttribute("ui.class")));
				hasDelta = true;
			}
		}

		// Check edges
		Iterator<Edge> iterE = this.g.edges().iterator();
		while (iterE.hasNext()) {
			Edge e = iterE.next();
			Node sn = e.getSourceNode();
			Node tn = e.getTargetNode();

			String edgeId = getStandardizedEdgeId(sn.getId(), tn.getId());
			if (!knownEdges.contains(edgeId)) {
				// Ensure both endpoint nodes exist in the delta graph before adding the edge.
				// A node may be "known" but still need to be present in deltaSg for the edge.
				if (deltaSg.getNode(sn.getId()) == null) {
					deltaSg.addNode(sn.getId(), MapAttribute.valueOf((String) sn.getAttribute("ui.class")));
				}
				if (deltaSg.getNode(tn.getId()) == null) {
					deltaSg.addNode(tn.getId(), MapAttribute.valueOf((String) tn.getAttribute("ui.class")));
				}
				deltaSg.addEdge(e.getId(), sn.getId(), tn.getId());
				hasDelta = true;
			}
		}

		if (hasDelta) {
			return deltaSg;
		}

		return null;
	}

	/**
	 * Mark the given nodes and edges as successfully received by the target agent.
	 */
	public synchronized void markAsKnown(String receiverName,
			SerializableSimpleGraph<String, MapAttribute> sgreceived) {
		Set<String> knownNodes = knownNodesByAgent.computeIfAbsent(receiverName, k -> new HashSet<>());
		Set<String> knownEdges = knownEdgesByAgent.computeIfAbsent(receiverName, k -> new HashSet<>());

		for (SerializableNode<String, MapAttribute> n : sgreceived.getAllNodes()) {
			knownNodes.add(n.getNodeId());
		}

		for (SerializableNode<String, MapAttribute> n : sgreceived.getAllNodes()) {
			for (String s : sgreceived.getEdges(n.getNodeId())) {
				knownEdges.add(getStandardizedEdgeId(n.getNodeId(), s));
			}
		}
	}

	/**
	 * After migration we load the serialized data and recreate the non serializable
	 * components (Gui,..)
	 */
	public synchronized void loadSavedData() {

		this.g = new SingleGraph("My world vision");
		this.g.setAttribute("ui.stylesheet", nodeStyle);

		openGui();

		Integer nbEd = 0;
		for (SerializableNode<String, MapAttribute> n : this.sg.getAllNodes()) {
			this.g.addNode(n.getNodeId()).setAttribute("ui.class", n.getNodeContent().toString());
			for (String s : this.sg.getEdges(n.getNodeId())) {
				this.g.addEdge(nbEd.toString(), n.getNodeId(), s);
				nbEd++;
			}
		}
		System.out.println("Loading done");
	}

	/**
	 * Method called before migration to kill all non serializable graphStream
	 * components
	 */
	private synchronized void closeGui() {
		// once the graph is saved, clear non serializable components
		if (this.viewer != null) {
			// Platform.runLater(() -> {
			try {
				this.viewer.close();
			} catch (NullPointerException e) {
				System.err.println(
						"Bug graphstream viewer.close() work-around - https://github.com/graphstream/gs-core/issues/150");
			}
			// });
			this.viewer = null;
		}
	}

	/**
	 * Method called after a migration to reopen GUI components
	 */
	private synchronized void openGui() {
		this.viewer = new FxViewer(this.g, FxViewer.ThreadingModel.GRAPH_IN_ANOTHER_THREAD);// GRAPH_IN_GUI_THREAD)
		viewer.enableAutoLayout();
		viewer.setCloseFramePolicy(FxViewer.CloseFramePolicy.CLOSE_VIEWER);
		viewer.addDefaultView(true);

		g.display();
	}

	/**
	 * Method called after a migration to reopen default GUI component
	 */
	private synchronized void openGui4() {

		Stage primaryStage = new Stage();
		StackPane newRoot = new StackPane();

		AnchorPane ap = new AnchorPane();

		FxViewer viewer = new FxViewer(g, FxViewer.ThreadingModel.GRAPH_IN_ANOTHER_THREAD);

		g.setAttribute("ui.antialias");
		g.setAttribute("ui.quality");
		viewer.enableAutoLayout();
		viewer.setCloseFramePolicy(FxViewer.CloseFramePolicy.CLOSE_VIEWER);
		// viewer.addDefaultView(true);

		// g.display();

		FxViewPanel panel = (FxViewPanel) viewer.addDefaultView(false, new FxGraphRenderer());
		ap.getChildren().add(panel);
		newRoot.getChildren().add(ap);
		primaryStage.setTitle(this.agentName);

		Scene scene = new Scene(newRoot, 800, 800);
		primaryStage.setScene(scene);
		primaryStage.show();

	}

	public void mergeMap(SerializableSimpleGraph<String, MapAttribute> sgreceived, String senderName) {
		// System.out.println("You should decide what you want to save and how");
		// System.out.println("We currently blindy add the topology");

		Set<String> senderKnownNodes = knownNodesByAgent.computeIfAbsent(senderName, k -> new HashSet<>());
		Set<String> senderKnownEdges = knownEdgesByAgent.computeIfAbsent(senderName, k -> new HashSet<>());

		for (SerializableNode<String, MapAttribute> n : sgreceived.getAllNodes()) {
			// System.out.println(n);
			boolean alreadyIn = false;
			senderKnownNodes.add(n.getNodeId()); // Record that the sender knows about this node

			// 1 Add the node
			Node newnode = null;
			try {
				newnode = this.g.addNode(n.getNodeId());
			} catch (IdAlreadyInUseException e) {
				alreadyIn = true;
				// System.out.println("Already in"+n.getNodeId());
			}
			if (!alreadyIn) {
				newnode.setAttribute("ui.label", newnode.getId());
				newnode.setAttribute("ui.class", n.getNodeContent().toString());
			} else {
				newnode = this.g.getNode(n.getNodeId());
				// 3 check its attribute. If it is below the one received, update it.
				if (((String) newnode.getAttribute("ui.class")) == MapAttribute.closed.toString()
						|| n.getNodeContent().toString() == MapAttribute.closed.toString()) {
					newnode.setAttribute("ui.class", MapAttribute.closed.toString());
				}
			}
		}

		// 4 now that all nodes are added, we can add edges
		for (SerializableNode<String, MapAttribute> n : sgreceived.getAllNodes()) {
			for (String s : sgreceived.getEdges(n.getNodeId())) {
				addEdge(n.getNodeId(), s);
				senderKnownEdges.add(getStandardizedEdgeId(n.getNodeId(), s)); // Record that the sender knows about
																				// this edge
			}
		}
		this.updateCount++;
		// System.out.println("Merge done");
	}

	/**
	 * 
	 * @return true if there exist at least one openNode on the graph
	 */
	public boolean hasOpenNode() {
		return (this.g.nodes()
				.filter(n -> n.getAttribute("ui.class") == MapAttribute.open.toString())
				.findAny()).isPresent();
	}

	public synchronized void addStench(String nodeId, int stenchValue, long timestamp) {
		this.stenches.put(nodeId, stenchValue);
		this.stenchTimestamps.put(nodeId, timestamp);
	}

	public synchronized void cleanOldStenches(long maxAgeMillis) {
		long currentTime = System.currentTimeMillis();
		List<String> toRemove = new ArrayList<>();
		for (String nodeId : stenchTimestamps.keySet()) {
			if (currentTime - stenchTimestamps.get(nodeId) > maxAgeMillis) {
				toRemove.add(nodeId);
			}
		}
		for (String nodeId : toRemove) {
			stenches.remove(nodeId);
			stenchTimestamps.remove(nodeId);
		}
	}

	public Integer getStench(String nodeId) {
		return this.stenches.get(nodeId);
	}

	public Long getStenchTimestamp(String nodeId) {
		return this.stenchTimestamps.get(nodeId);
	}

	public Set<String> getStenchNodes() {
		return new HashSet<>(this.stenches.keySet());
	}

	/**
	 * Triangulation: Returns the set of possible Golem locations
	 * by computing the intersection of the neighborhoods of the given stench nodes.
	 * Since a Golem leaves a stench at distance 1, it must be in the neighborhood
	 * of EVERY node where a stench is currently perceived.
	 */
	public synchronized Set<String> getGolemPossibleLocations(Set<String> stenchNodesId) {
		if (stenchNodesId == null || stenchNodesId.isEmpty()) {
			return new HashSet<>();
		}

		Set<String> possiblePositions = null;

		for (String nodeId : stenchNodesId) {
			Node n = this.g.getNode(nodeId);
			if (n != null) {
				Set<String> neighbors = n.neighborNodes()
						.map(Node::getId)
						.collect(Collectors.toSet());

				if (possiblePositions == null) {
					possiblePositions = neighbors;
				} else {
					possiblePositions.retainAll(neighbors);
				}
			}
		}

		return possiblePositions != null ? possiblePositions : new HashSet<>();
	}

	/**
	 * Returns the list of node IDs adjacent to the given nodeId.
	 */
	public synchronized List<String> getNeighbors(String nodeId) {
		List<String> neighbors = new ArrayList<>();
		Node n = this.g.getNode(nodeId);
		if (n != null) {
			n.neighborNodes().forEach(neighbor -> neighbors.add(neighbor.getId()));
		}
		return neighbors;
	}

	/**
	 * Siege Coordination: Update the latest known state of the Golem siege.
	 */
	public synchronized void updateSiegeStatus(String golemPos, String golemName, String agentName, String agentPos,
			Set<String> holes, long timestamp) {
		if (timestamp >= this.siegeTimestamp && golemPos != null) {
			this.siegeGolemPos = golemPos;
			this.siegeGolemName = golemName;
			this.siegeTimestamp = timestamp;
		}
		if (holes != null) {
			this.siegeHoles = holes;
		}
		if (agentName != null && agentPos != null) {
			this.siegeStaff.put(agentName, agentPos);
			this.siegeStaffTimestamps.put(agentName, timestamp);
		}
	}

	/**
	 * Clean old siege staff positions to prevent indefinitely avoiding ghost
	 * allies.
	 */
	public synchronized void cleanOldSiegeData(long maxAgeMillis) {
		long currentTime = System.currentTimeMillis();
		// Clean staff
		List<String> toRemove = new ArrayList<>();
		for (String agent : siegeStaffTimestamps.keySet()) {
			if (currentTime - siegeStaffTimestamps.get(agent) > maxAgeMillis) {
				toRemove.add(agent);
			}
		}
		for (String agent : toRemove) {
			siegeStaff.remove(agent);
			siegeStaffTimestamps.remove(agent);
		}

		// If the siege itself is too old, clear it
		if (currentTime - this.siegeTimestamp > maxAgeMillis + 2000) {
			this.siegeGolemPos = null;
			this.siegeGolemName = null;
			this.siegeHoles.clear();
		}
	}

	public String getSiegeGolemPos() {
		return siegeGolemPos;
	}

	public String getSiegeGolemName() {
		return siegeGolemName;
	}

	public Set<String> getSiegeHoles() {
		return new HashSet<>(siegeHoles);
	}

	public List<String> getSiegeStaffLocations() {
		return new ArrayList<>(siegeStaff.values());
	}

}