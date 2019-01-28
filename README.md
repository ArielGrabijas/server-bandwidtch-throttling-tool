# server-bandwidtch-throttling-tool

 ConcurrentServerGateway is a thread-safe server bandwidth throttling tool, it regulates network traffic and minimize bandwidth congestion. 
 
 Before user's request can be handled by the server, it has to gain enter permission from ConcurrentServerGateway.
 Therefore server has to call out work(user) method to check if he can proceed any further with user's request.
 After request is successfully handled, permission has to be released using release(user).
 
 Invariants:
 1. There can be no more than MAX_USERS users with granted permission at the same time.
 2. Each user can have no more than MAX_REQUESTS_PER_USER requests with granted permissions.
 3. There can't be more than MAX_REQUESTS requests with granted permission in total. 
 4. If user can't acquire permission, it has to wait for not longer than MAX_AWAIT milliseconds for result.
  
 Thread-safety policy:
 ConcurrentServerGateway is fully thread-safe and needs no client side locking.
 ConcurrentServerGateway has mutable state consisting of two objects: grantedRequests and awaitingRequests, 
 both of them are guarded by 'this' lock. 
 State transitions atomicity and invariants are preserved by 'this' lock.
