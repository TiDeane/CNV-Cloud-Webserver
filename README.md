## Games@Cloud

### Current Architecture

The webserver is instrumented with Javassist to extract metrics, using the tool ``MetricsExtractor``. The tool uses threads and extracts the number of instructions and number of memory accesses of each new request. The metrics are obtained by the webserver handlers and stored in the ``metrics.txt`` file. Later on, they will be stored in DynamoDB instead (with a different structure). 

### AWS System Configuration

The script ``create-image.sh`` creates an AMI of the webserver. The script ``launch-deployment-template.sh`` can then be used to launch the deployment with alarms, load balancing and auto scaling.

- The load balancer is an HTTP ELB launched in us-east-1a.
- The AutoScalingGroup has a desired capacity of 1, minimum capacity of 1 and maximum capacity of 2. It uses step scaling, scaling out when average CPU utilization is higher than 50% for 1 minute, and scaling in when average CPU utilization is lower than 25% for 1 minute.
  - It is configured to launch instances with the AMI created previously.

## Endpoints

This project contains three sub-projects:

1. `capturetheflag` - the Capture the Flag workload
2. `fifteenpuzzle` - the 15-Puzzle Solver workload
3. `gameoflife` - the Conway's Game of Life workload
4. `webserver` - the web server exposing the functionality of the workloads

Refer to the `README.md` files of the sub-projects to get more details about each specific sub-project.

### How to build everything

1. Make sure your `JAVA_HOME` environment variable is set to Java 11+ distribution
2. Run `mvn clean package`
