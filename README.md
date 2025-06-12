## Games@Cloud

### Current Architecture

The webserver is instrumented with Javassist to extract metrics, using the tool ``MetricsExtractor``. The tool uses threads and extracts the number of instructions and number of memory accesses of each new request. The metrics are obtained by the webserver handlers and stored in the ``metrics.txt`` file. Later on, they will be stored in DynamoDB instead (with a different structure). 

### AWS System Configuration

- The script ``create-image.sh`` creates an AMI of the webserver. The script ``launch-deployment-template.sh`` can then be used to launch the deployment with alarms, load balancing and auto scaling with AWS own resources.
- The script ``launch-lb.sh`` creates and launches our loadbalancer/autoscaler in an EC2 instance.
- Our Autoscaler has a desired capacity of 1, minimum capacity of 1 and maximum capacity of 2. It uses step scaling, scaling out when average CPU utilization is higher than 30%, and scaling in when average CPU utilization is lower than 10%. It performs scaling events at least 5min apart from each other. It is configured to launch instances with the AMI created previously.
- The script ``function-register.sh`` launches our lambda instances in AWS. 

## Endpoints

This project contains eight sub-projects:

1. `capturetheflag` - the Capture the Flag workload
2. `fifteenpuzzle` - the 15-Puzzle Solver workload
3. `gameoflife` - the Conway's Game of Life workload
4. `webserver` - the web server exposing the functionality of the workloads
5. `common` - the web server exposing the functionality of the workloads
6. `dynamodbclient` - the deployment and handling of our dynamoDB
7. `loadbalancer` - the loadbalancer and autoscaler workload

Refer to the `README.md` files of the sub-projects to get more details about each specific sub-project.

### How to build everything

1. Make sure your `JAVA_HOME` environment variable is set to Java 11+ distribution
2. Run `mvn clean package`
3. To build the entire project from scratch, including the AMI image, run the script ``general-deploy.sh``
