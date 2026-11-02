(ns or-tools.ports
  "Role-sized ports through which each bounded context reaches a solver.
   Each method takes a promoted plan and returns a hive-dsl Result.")

(defprotocol ICpSatEngine
  (solve-cp-sat [engine plan options]
    "Solve a CP-SAT plan under promoted options."))

(defprotocol ILinearEngine
  (solve-linear [engine plan options]
    "Solve a linear or mixed-integer plan under promoted options."))

(defprotocol IFlowEngine
  (solve-max-flow [engine plan]
    "Solve a max-flow plan. The ok value holds :status, :value and :flows.")
  (solve-min-cost-flow [engine plan]
    "Solve a min-cost-flow plan. The ok value holds :status, :cost and :flows."))

(defprotocol IAssignmentEngine
  (solve-assignment [engine plan]
    "Solve a linear-sum assignment plan."))

(defprotocol IKnapsackEngine
  (solve-knapsack [engine plan]
    "Solve a knapsack plan."))

(defprotocol IRoutingEngine
  (solve-routing [engine plan]
    "Solve a vehicle-routing plan."))
