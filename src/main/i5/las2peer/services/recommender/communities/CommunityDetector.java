package i5.las2peer.services.recommender.communities;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;

import i5.las2peer.services.recommender.communities.igraph.Igraph;
import i5.las2peer.services.recommender.communities.webocd.Cover;
import i5.las2peer.services.recommender.communities.webocd.CustomGraph;
import i5.las2peer.services.recommender.communities.webocd.OcdAlgorithmException;
import i5.las2peer.services.recommender.communities.webocd.RandomWalkLabelPropagationAlgorithm;
import i5.las2peer.services.recommender.communities.webocd.SpeakerListenerLabelPropagationAlgorithm;
import i5.las2peer.services.recommender.librec.data.DenseVector;
import i5.las2peer.services.recommender.librec.data.MatrixEntry;
import i5.las2peer.services.recommender.librec.data.SparseMatrix;
import i5.las2peer.services.recommender.librec.data.SparseVector;
import i5.las2peer.services.recommender.librec.data.VectorEntry;
import i5.las2peer.services.recommender.librec.util.Logs;
import y.base.Edge;
import y.base.Node;

public class CommunityDetector {
	
	private CommunityDetectionAlgorithm algorithm;
	
	private boolean overlapping = true;

	private SparseMatrix graph;
	
	private SparseMatrix membershipsMatrix;
	
	// Community memberships vector, only set when using Walktrap, not when using OCD algorithms
	private DenseVector membershipsVector;
	
	// DMID parameters
	private int dmidLeadershipIterationBound = 1000;
	private double dmidLeadershipPrecisionFactor = 0.001;
	private double dmidProfitabilityDelta = 0.1;
	
	// Walktrap parameters
	private int walktrapSteps = 2;
	
	// SLPA parameters
	private double slpaProbabilityThreshold = 0.15;
	private int slpaMemorySize = 100;
	
	private int communityDetectionTime;
	
	
	public enum CommunityDetectionAlgorithm{
		WALKTRAP, DMID, SLPA
	}
	
	
	public void setAlgorithm(CommunityDetectionAlgorithm algorithm){
		this.algorithm = algorithm;
	}
	
	public void setGraph(SparseMatrix graph){
		this.graph = graph;
	}
	
	public void setDmidParameters(int iterationBound, double precisionFactor, double profitabilityDelta){
		dmidLeadershipIterationBound = iterationBound;
		dmidLeadershipPrecisionFactor = precisionFactor;
		dmidProfitabilityDelta = profitabilityDelta;
	}
	
	public void setWalktrapParameters(int steps){
		walktrapSteps = steps;
	}
	
	public void setSlpaParameters(double probabilityThresh, int memorySize){
		slpaProbabilityThreshold = probabilityThresh;
		slpaMemorySize = memorySize;
	}
	
	public void setOverlapping(boolean overlapping){
		this.overlapping = overlapping;
	}
	
	public void detectCommunities() throws OcdAlgorithmException, InterruptedException{
		Stopwatch sw = Stopwatch.createStarted();

		switch(algorithm){
			case WALKTRAP:
				detectWalktrap();
				break;
			case DMID:
				detectDmid();
				break;
			case SLPA:
				detectSlpa();
				break;
			default:
				break;
		}
		
		sw.stop();
		communityDetectionTime = (int) sw.elapsed(TimeUnit.SECONDS);
	}
	
	public SparseMatrix getMemberships(){
		return membershipsMatrix;
	}
	
	public DenseVector getMembershipsVector(){
		return membershipsVector;
	}
	
	public int getNumCommunities(){
		return membershipsMatrix.numColumns();
	}
	
	public int getComputationTime(){
		return communityDetectionTime;
	}
	
	private void detectDmid() throws OcdAlgorithmException, InterruptedException {
		Logs.info(String.format("DMID: [LIB, LPF, PD] = [%s, %s, %s]",
				dmidLeadershipIterationBound, dmidLeadershipPrecisionFactor, dmidProfitabilityDelta));
		
		RandomWalkLabelPropagationAlgorithm dmidAlgo = new RandomWalkLabelPropagationAlgorithm();
		CustomGraph customGraph = getWebOCDCustomGraph();
		
		Map<String, String> parameters = new HashMap<String, String>();
		parameters.put("leadershipIterationBound", Integer.toString(dmidLeadershipIterationBound));
		parameters.put("leadershipPrecisionFactor", Double.toString(dmidLeadershipPrecisionFactor));
		parameters.put("profitabilityDelta", Double.toString(dmidProfitabilityDelta));
		dmidAlgo.setParameters(parameters);
		
		Cover cover = dmidAlgo.detectOverlappingCommunities(customGraph);
		
		membershipsMatrix = cover.getMemberships();
		if (!overlapping)
			makeNonOverlapping();
		membershipsVector = computeMembershipsVector();
	}
	
	private void detectSlpa() throws OcdAlgorithmException, InterruptedException {
		Logs.info(String.format("SLPA: [PT, MS] = [%s, %s]",
				slpaProbabilityThreshold, slpaMemorySize));
		
		SpeakerListenerLabelPropagationAlgorithm slpaAlgo = new SpeakerListenerLabelPropagationAlgorithm();
		CustomGraph customGraph = getWebOCDCustomGraph();
		
		Map<String, String> parameters = new HashMap<String, String>();
		parameters.put("probabilityThreshold", Double.toString(slpaProbabilityThreshold));
		parameters.put("memorySize", Integer.toString(slpaMemorySize));
		slpaAlgo.setParameters(parameters);
		
		Cover cover = slpaAlgo.detectOverlappingCommunities(customGraph);
		
		membershipsMatrix = cover.getMemberships();
		if (!overlapping)
			makeNonOverlapping();
		membershipsVector = computeMembershipsVector();
	}
	
	private CustomGraph getWebOCDCustomGraph() {
		// Construct a CustomGraph to be used by WebOCD from the SparseMatrix graph
		
		CustomGraph customGraph = new CustomGraph();
		
		BiMap<Integer, Node> userNodeMap = HashBiMap.create();
		
		int numUsers = graph.numRows();
		
		for (int user = 0; user < numUsers; user++){
			Node node = customGraph.createNode();
			userNodeMap.put(user, node);
		}
		
		for (MatrixEntry e : graph){
			int user1 = e.row();
			int user2 = e.column();
			double weight = e.get();
			Node node1 = userNodeMap.get(user1);
			Node node2 = userNodeMap.get(user2);
			
			Edge edge = customGraph.createEdge(node1, node2);
			customGraph.setEdgeWeight(edge, weight);
		}
		
		return customGraph;
	}
	
	private void makeNonOverlapping() {
		int numNodes = membershipsMatrix.numRows();
		int numCommunities = membershipsMatrix.numColumns();
		
		Table<Integer, Integer, Double> membershipsTable = HashBasedTable.create();
		Multimap<Integer, Integer> membershipsColMap = HashMultimap.create();

		for (int node = 0; node < numNodes; node++){
			// get community with highest membership level and store in vector
			SparseVector communitiesVector = membershipsMatrix.row(node);
			double maxLevel = 0;
			int community = 0;
			for (VectorEntry e : communitiesVector){
				double level = e.get();
				if (level > maxLevel){
					maxLevel = level;
					community = e.index();
				}
			}
			membershipsTable.put(node, community, 1.0);
			membershipsColMap.put(community, node);
		}
		membershipsMatrix = new SparseMatrix(numNodes, numCommunities, membershipsTable, membershipsColMap);
	}

	private DenseVector computeMembershipsVector(){
		int numNodes = membershipsMatrix.numRows();
		
		DenseVector vector = new DenseVector(numNodes);
		
		for (int node = 0; node < numNodes; node++){
			// get community with highest membership level and store in vector
			SparseVector communitiesVector = membershipsMatrix.row(node);
			double maxLevel = 0;
			int community = 0;
			for (VectorEntry e : communitiesVector){
				double level = e.get();
				if (level > maxLevel){
					maxLevel = level;
					community = e.index();
				}
			}
			vector.set(node, community);
		}
		
		return vector;
	}

	private void detectWalktrap() {
		Logs.info(String.format("Walktrap: [steps] = [%s]", walktrapSteps));
		
		Igraph igraph = new Igraph();
		
		igraph.setGraph(graph);
		igraph.detectCommunitiesWalktrap(walktrapSteps);
		membershipsMatrix = igraph.getMembershipsMatrix();
		membershipsVector = igraph.getMembershipsVector();
	}
}
