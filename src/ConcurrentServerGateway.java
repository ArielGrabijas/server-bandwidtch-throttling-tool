import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * @author Ariel Grabijas
 * 
 * ConcurrentServerGateway is a thread-safe server bandwidth throttling tool, it regulates network traffic and minimize bandwidth congestion. 
 * 
 * Before user's request can be handled by the server, it has to gain enter permission from ConcurrentServerGateway.
 * Therefore server has to call out work(user) method to check if he can proceed any further with user's request.
 * After request is successfully handled, permission has to be released using release(user).
 * 
 * Invariants:
 * 1. There can be no more than MAX_USERS users with granted permission at the same time.
 * 2. Each user can have no more than MAX_REQUESTS_PER_USER requests with granted permissions.
 * 3. There can't be more than MAX_REQUESTS requests with granted permission in total. 
 * 4. If user can't acquire permission, it has to wait for not longer than MAX_AWAIT milliseconds for result.
 * 
 * Thread-safety policy:
 * ConcurrentServerGateway is fully thread-safe and needs no client side locking.
 * ConcurrentServerGateway has mutable state consisting of two objects: grantedRequests and awaitingRequests, 
 * both of them are guarded by 'this' lock. 
 * State transitions atomicity and invariants are preserved by 'this' lock.
 */

public final class ConcurrentServerGateway {
	private final int MAX_USERS;
	private final int MAX_REQUESTS;
	private final int MAX_AWAIT;
	private final int MAX_REQUESTS_PER_USER;
	
	// stores all awaiting requests.
	// key - awaiting user's identifier, value - list of awaiting permission inquiries
	private final ConcurrentMap<String, BlockingQueue<String>> awaitingRequests;
	
	// stores all requests with granted permission.
	// key - represents identifier of user who successfully obtained permission, value - number of granted permissions.
	private final ConcurrentMap<String, Integer> grantedRequests;
	
	public ConcurrentServerGateway(final int MAX_USERS, final int MAX_REQUESTS, final int MAX_REQUESTS_PER_USER, final int MAX_AWAIT) {
		if(MAX_REQUESTS_PER_USER <= 0 || MAX_USERS <= 0 || MAX_AWAIT <= 0 || MAX_REQUESTS <= 0)
			throw new IllegalArgumentException();
		
		this.MAX_USERS = MAX_USERS;
		this.MAX_REQUESTS = MAX_REQUESTS;
		this.MAX_REQUESTS_PER_USER = MAX_REQUESTS_PER_USER;
		this.MAX_AWAIT = MAX_AWAIT;
		this.grantedRequests = new ConcurrentHashMap<String, Integer>();
		this.awaitingRequests = new ConcurrentHashMap<String, BlockingQueue<String>>();
	}
	
	/**
	 * Returns true if it is possible for the user to enter the server in given period of time,
	 * or false if not.
	 * 
	 * @param user - caller identifier
	 * @return true if permission granted, false if not
	 * @throws InterruptedException
	 */
	public boolean work(String user) throws InterruptedException {
		if(user.isEmpty() || user == null)
			throw new IllegalArgumentException();
		
		synchronized(this) {                  
			if(canAllowRequest(user)) {
				addUser(user);
				return true;
			}
		}
		
		return awaitRequest(user, Instant.now(), MAX_AWAIT);
	}
	
		// Guarded by outer lock : this
		private boolean canAllowRequest(String user) {
			int requests = countAllRequests();
			
			if(grantedRequests.containsKey(user)) {
				if(grantedRequests.get(user) < MAX_REQUESTS_PER_USER && requests < MAX_REQUESTS)
					return true;
				else 
					return false; 
				
			}
			if(grantedRequests.size() < MAX_USERS && requests < MAX_REQUESTS)
				return true;
			else 
				return false;
		}
		
			private int countAllRequests() {
				return this.grantedRequests.values().stream().mapToInt(i -> i).sum();
			}

		// Guarded by outer lock : this
		private void addUser(String user) throws InterruptedException {
			if(this.grantedRequests.containsKey(user)) 
				addOldUserRequest(user);
			else 
				addNewUser(user);
			collectWorkTestData(user, true); // test, to be removed in production version.
		}

			// Guarded by outer lock : this
			private void addNewUser(String userName) throws InterruptedException {
				grantedRequests.put(userName, 1);
			}
			
			// Guarded by outer lock : this
			private void addOldUserRequest(String userName) throws InterruptedException {
				grantedRequests.put(userName, grantedRequests.get(userName)+1);
			}
		 
		private boolean awaitRequest(String user, Instant begin, long maxAwaitTime) throws InterruptedException {
			this.awaitingRequests.putIfAbsent(user, new SynchronousQueue<String>());
			
			if(this.awaitingRequests.get(user).offer(user, maxAwaitTime, TimeUnit.MILLISECONDS)) { 
				synchronized(this) {
					if(canAllowRequest(user)) {
						addUser(user);
						return true;
					}		
				}
				awaitRequestAgain(user, begin);
			}

			synchronized(this) {
				collectWorkTestData(user, false); // test, to be removed in production version.
				return false;
			}
		}

			private void awaitRequestAgain(String user, Instant begin) throws InterruptedException {
				long passedTime = Duration.between(begin, Instant.now()).getNano()/1000000;
				long timeLeft = MAX_AWAIT - passedTime;
				if(timeLeft <= this.MAX_AWAIT && timeLeft > 0) 
					awaitRequest(user, begin, timeLeft);
			}
			
		/**
		 * Releases occupied spot on grantedRequests, and allows awaiting request from to attempt to receive permission.
		 * 1. When releasing permission spot, caller has priority over other awaiting users to reuse this spot for his awaiting request.
		 * 2. If caller has no awaiting requests, then users who are already on the server have priority to gain access over those who are not.
		 * @param user - caller identifier
		 * @return true if spot has been released, false otherwise.
		 * @throws InterruptedException
		 */
		public synchronized boolean release(String user) throws InterruptedException {
			if(user.isEmpty() || user == null)
				throw new IllegalArgumentException();
			
			if(this.grantedRequests.containsKey(user)) {
				updateState(user);
				if(!allowCallerRequest(user)) { 
					allowAnyRequest();
				}
				return true;
			}
			return false;
		}
		
		
			private void updateState(String user) {
				if(this.grantedRequests.get(user) > 1) 
					this.grantedRequests.put(user, this.grantedRequests.get(user)-1);
				else 
					this.grantedRequests.remove(user);
				collectReleaseTestData(user); // test, to be removed in production version.
			}
		
			private boolean allowCallerRequest(String user) throws InterruptedException {
				if(this.awaitingRequests.containsKey(user)) {
					String allowedUser = this.awaitingRequests.get(user).poll();
					if(allowedUser != null && !allowedUser.isEmpty())
						return true;
				}
				return false;
			}
			
			private void allowAnyRequest() throws InterruptedException {
				if(!allowOldUser())
					allowNewUser();
			}
				
				private boolean allowOldUser() {
					for(Map.Entry<String, BlockingQueue<String>>  i : this.awaitingRequests.entrySet()) {
						if(isUserOnServer(i.getKey())) {
							if(tryAllowRequest(i)) {
								return true;
							}
						}
					}
					return false;
				}
				
				private void allowNewUser() {
					for(Map.Entry<String, BlockingQueue<String>>  i : this.awaitingRequests.entrySet()) {
						if(!isUserOnServer(i.getKey())) {
							if(tryAllowRequest(i)) {
								tryAllowMoreRequests(i);
								break;
							}
						}
					}
				}
				
					private boolean isUserOnServer(String user) {
						return this.grantedRequests.containsKey(user);
					}
				
					private boolean tryAllowRequest(Map.Entry<String, BlockingQueue<String>>  userAwaitingRequests) {
						if(canAllowRequest(userAwaitingRequests.getKey())) { // because i am not releasing this user by release, so i dont know if he can enter
							String allowedRequest = userAwaitingRequests.getValue().poll();
							if(allowedRequest != null && !allowedRequest.isEmpty()) {
								return true;
							}
						}
						return false;
					}
					
					// After old user released his last permission, and new one enters the server, it is possible
					// that there was more than one free permits, which couldn't be granted because
					// MAX_USERS invariant was violated. Therefore this method tries to gain more than one permits.
					private void tryAllowMoreRequests(Map.Entry<String, BlockingQueue<String>>  userAwaitingRequests) {
						if(canAllowRequest(userAwaitingRequests.getKey())) {
							String allowedRequest = userAwaitingRequests.getValue().poll();
							if(allowedRequest != null && !allowedRequest.isEmpty()) 
								tryAllowMoreRequests(userAwaitingRequests);
						}
					}
	
	// TEST API. To be removed in production version:
	private final LinkedHashMap<Operation, ConcurrentMap<String, Integer>> stateTransitionLog = new LinkedHashMap<Operation, ConcurrentMap<String, Integer>>();
	public static enum OperationType {work, release};
			
	public final class Operation{
		public final OperationType op;
		public final String user;
		public final boolean result;

		public Operation(String user, OperationType op, boolean result) {
			this.op = op;
			this.user = user;
			this.result = result;
		}
		
		public String toString() {
			return " user: " + user + " operation: " + String.valueOf(op) + " result: " + result;
		}
	}
	
	private void collectReleaseTestData(String user) {
		stateTransitionLog.put(new Operation(user, OperationType.release, true), new ConcurrentHashMap<String, Integer>(this.grantedRequests));
	}
	
	private void collectWorkTestData(String user, boolean result) {
		stateTransitionLog.put(new Operation(user, OperationType.work, result), new ConcurrentHashMap<String, Integer>(this.grantedRequests));
	}
	
	public Map<Operation, ConcurrentMap<String, Integer>> getFinalState(){
		return this.stateTransitionLog;
	}

}
