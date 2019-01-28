import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 
 * @author Ariel Grabijas
 *
 */
public class ConcurrentServerGatewayStressTest {
	private static final int threadsNr = 3;
	private static final ExecutorService pool = Executors.newCachedThreadPool();
	
	private final int MAX_USERS = 2;
	private final int MAX_REQUESTS = 5;
	private final int MAX_REQUESTS_PER_USER = 3;
	private final int MAX_AWAIT = 200;
	
	private final ConcurrentServerGateway gateway;

	private final CyclicBarrier barrier;
	
	public ConcurrentServerGatewayStressTest() {
		gateway = new ConcurrentServerGateway(MAX_USERS, MAX_REQUESTS, MAX_REQUESTS_PER_USER, MAX_AWAIT);
		barrier = new CyclicBarrier(threadsNr+1+ (threadsNr*(MAX_REQUESTS_PER_USER+2)));
	}
	
	public void startStressTest() {
		try {
			System.out.println("Starting " + threadsNr + " threads");
			
			for(int i = 0; i < threadsNr; i++) {
				pool.execute(new User());
			}
			
			barrier.await();
			System.out.println("Threads are ready. Test begins: " + LocalTime.now() + "\n");
			barrier.await();
			System.out.println(gateway.getFinalState());
			
			checkStateTransitionsAtomicity(gateway.getFinalState());
			checkStateTransitionsCorrectness(gateway.getFinalState());
			
			System.out.println("Test ends: " + LocalTime.now() + "\n");
		} catch (InterruptedException | BrokenBarrierException e) {
			e.printStackTrace();
		}
	}
		
	private class User implements Runnable {
		private boolean isClone = false;
		private String user;
		
		public User() {}
		
		public User(String user) {
			this.user = user;
			this.isClone = true;
		}
		
		@Override
		public void run() {
			try {
				int sleepTime = rng() % MAX_AWAIT;
				if(!isClone) {
					user = Integer.toString(rng());
					for(int i = 0; i < MAX_REQUESTS_PER_USER+2; i++)
						pool.execute(new User(user));
				}
				
				barrier.await(); // wait until other threads are ready.
				
				if(gateway.work(user)) {
					Thread.sleep(sleepTime);
					gateway.release(user);
				}
				
				barrier.await(); // wait until other threads are done.
			} catch (InterruptedException | BrokenBarrierException e) {
				e.printStackTrace();
			}
		}
		
		private int rng() {
			return Math.abs(xorShift((this.hashCode() ^ (int)System.nanoTime())));
		}
	}
		
		private static int xorShift(int y) {
			y ^= (y << 6);
			y ^= (y >>> 21);
			y ^= (y << 7);
			return y;
		}
	
	/**
	 * Validates, that there is only one difference between each state change if work/release : true, or no differences when work : false
	 * @param stateTransitionLog
	 */
	private void checkStateTransitionsAtomicity(Map<ConcurrentServerGateway.Operation, ConcurrentMap<String, Integer>> stateTransitionLog) {
		List<Map.Entry<ConcurrentServerGateway.Operation, ConcurrentMap<String, Integer>>> values 
			= new LinkedList<Map.Entry<ConcurrentServerGateway.Operation, ConcurrentMap<String, Integer>>>();
		
		for(Map.Entry<ConcurrentServerGateway.Operation, ConcurrentMap<String, Integer>> i : stateTransitionLog.entrySet()) {
			values.add(i);
		}
		
		for(int i = 1; i < values.size(); i++) { // iterate over current operation
			Map<String, Integer> currentState = values.get(i).getValue();
			Map<String, Integer> previousState = values.get(i-1).getValue();
						
			ConcurrentServerGateway.OperationType currentOperationType = values.get(i).getKey().op;
			boolean operationResult = values.get(i).getKey().result;
			String user = values.get(i).getKey().user;

			if(currentOperationType.equals(ConcurrentServerGateway.OperationType.work)) {
				Map<String, Integer> stateDifferences = getDifferencesAfterWork(currentState, previousState);
				if(operationResult) // work(user) : true
					assertTrue(stateDifferences.size()==1);
				else // work(user) : false
					assertTrue(stateDifferences.size()==0);
			}
			else if(currentOperationType.equals(ConcurrentServerGateway.OperationType.release)) {
				Map<String, Integer> stateDifferences = getDifferencesAfterRelease(currentState, previousState);
				assertTrue(stateDifferences.size()==1);
			}
		}
	}
	
		private Map<String, Integer> getDifferencesAfterWork(Map<String, Integer> currentState, Map<String, Integer> previousState){
			Map<String, Integer> stateDifferences = currentState
					.entrySet().stream()
					.filter(k -> k.getValue() != previousState.get(k.getKey()))
					.collect(Collectors.toMap(l -> l.getKey(), l -> l.getValue()));
			return stateDifferences;
		}
	
		private Map<String, Integer> getDifferencesAfterRelease(Map<String, Integer> currentState, Map<String, Integer> previousState){
			Map<String, Integer> stateDifferences = previousState
					.entrySet().stream()
					.filter(k -> k.getValue() != currentState.get(k.getKey()))
					.collect(Collectors.toMap(x -> x.getKey(), x -> x.getValue()));
			return stateDifferences;
		}
		
	/*
	 * Validates, that after each state transition, caused by work/release, tested object state is valid:
	 * 1. After 
	 */
	private void checkStateTransitionsCorrectness(Map<ConcurrentServerGateway.Operation, ConcurrentMap<String, Integer>> stateTransitionLog){
		List<Map.Entry<ConcurrentServerGateway.Operation, ConcurrentMap<String, Integer>>> states 
			= new LinkedList<Map.Entry<ConcurrentServerGateway.Operation, ConcurrentMap<String, Integer>>>();
		
		for(Map.Entry<ConcurrentServerGateway.Operation, ConcurrentMap<String, Integer>> i : stateTransitionLog.entrySet()) {
			states.add(i);
		}
		
		for(int i = 1; i < states.size(); i++) { // iterate over current operation
			Map<String, Integer> currentState = states.get(i).getValue();
			Map<String, Integer> previousState = states.get(i-1).getValue();
			boolean operationResult = states.get(i).getKey().result;
			
			ConcurrentServerGateway.OperationType currentOperation = states.get(i).getKey().op;
			String user = states.get(i).getKey().user;

			if(currentOperation.equals(ConcurrentServerGateway.OperationType.work)) {
				if(operationResult) // work(user) : true
					assertTrue(canAllowRequest(user, previousState));
				else { // work(user) : false
					assertEquals(currentState, previousState);
				}
			}
			else if(currentOperation.equals(ConcurrentServerGateway.OperationType.release)) {
				if(currentState.containsKey(user))
					assertEquals((int)currentState.get(user), (int)previousState.get(user)-1);
				else
					assertTrue((int)previousState.get(user)==1);
			}
		}
	}
	
		private boolean canAllowRequest(String user, Map<String, Integer> currentState) {
			int requests = countAllRequests(currentState);
			if(currentState.containsKey(user)) {
				//System.out.println("Contained");
				if(currentState.get(user) < MAX_REQUESTS_PER_USER && requests < MAX_REQUESTS)
					return true;
				else 
					return false; // must be, otherwise user with MAX_REQUESTS will take second spot.
				
			}
			if(currentState.size() < MAX_USERS && requests < MAX_REQUESTS)
				return true;
			else 
				return false;
		}
		
			private int countAllRequests(Map<String, Integer> currentState) {
				return currentState.values().stream().mapToInt(i -> i).sum();
			}
}
